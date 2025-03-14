/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.mem

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import chisel3.ExcitingUtils
import utils._
import xs.utils._
import xiangshan._
import xiangshan.backend.execute.fu.fpu.FPU
import xiangshan.backend.rob.RobLsqIO
import xiangshan.cache._
import xiangshan.frontend.FtqPtr
import xiangshan.ExceptionNO._

class LqPtr(implicit p: Parameters) extends CircularQueuePtr[LqPtr](
  p => p(XSCoreParamsKey).LoadQueueSize
){
}

object LqPtr {
  def apply(f: Bool, v: UInt)(implicit p: Parameters): LqPtr = {
    val ptr = Wire(new LqPtr)
    ptr.flag := f
    ptr.value := v
    ptr
  }
}

trait HasLoadHelper { this: XSModule =>
  def rdataHelper(uop: MicroOp, rdata: UInt): UInt = {
    val fpWen = uop.ctrl.fpWen
    LookupTree(uop.ctrl.fuOpType, List(
      LSUOpType.lb   -> SignExt(rdata(7, 0) , XLEN),
      LSUOpType.lh   -> SignExt(rdata(15, 0), XLEN),
      /*
          riscv-spec-20191213: 12.2 NaN Boxing of Narrower Values
          Any operation that writes a narrower result to an f register must write
          all 1s to the uppermost FLEN−n bits to yield a legal NaN-boxed value.
      */
      LSUOpType.lw   -> Mux(fpWen, FPU.box(rdata, FPU.S), SignExt(rdata(31, 0), XLEN)),
      LSUOpType.ld   -> Mux(fpWen, FPU.box(rdata, FPU.D), SignExt(rdata(63, 0), XLEN)),
      LSUOpType.lbu  -> ZeroExt(rdata(7, 0) , XLEN),
      LSUOpType.lhu  -> ZeroExt(rdata(15, 0), XLEN),
      LSUOpType.lwu  -> ZeroExt(rdata(31, 0), XLEN),
    ))
  }
}

class LqEnqIO(implicit p: Parameters) extends XSBundle {
  val canAccept = Output(Bool())
  val sqCanAccept = Input(Bool())
  val needAlloc = Vec(exuParameters.LsExuCnt, Input(Bool()))
  val req = Vec(exuParameters.LsExuCnt, Flipped(ValidIO(new MicroOp)))
  val resp = Vec(exuParameters.LsExuCnt, Output(new LqPtr))
  val reqNum = Input((UInt(exuParameters.LsExuCnt.W)))
}

class LqPaddrWriteBundle(implicit p: Parameters) extends XSBundle {
  val paddr = Output(UInt(PAddrBits.W))
  val lqIdx = Output(new LqPtr)
}

class LqTriggerIO(implicit p: Parameters) extends XSBundle {
  val hitLoadAddrTriggerHitVec = Input(Vec(TriggerNum, Bool()))
  val lqLoadAddrTriggerHitVec = Output(Vec(TriggerNum, Bool()))
}

// Load Queue
class LoadQueue(implicit p: Parameters) extends XSModule
  with HasDCacheParameters
  with HasCircularQueuePtrHelper
  with HasLoadHelper
  with HasPerfEvents
{
  val io = IO(new Bundle() {
    val enq = new LqEnqIO
    val brqRedirect = Flipped(ValidIO(new Redirect))
    val loadPaddrIn = Vec(LoadPipelineWidth, Flipped(Valid(new LqPaddrWriteBundle)))
    val loadIn = Vec(LoadPipelineWidth, Flipped(Valid(new LqWriteBundle)))
    val storeIn = Vec(StorePipelineWidth, Flipped(Valid(new LsPipelineBundle)))
    val s2_load_data_forwarded = Vec(LoadPipelineWidth, Input(Bool()))
    val s3_delayed_load_error = Vec(LoadPipelineWidth, Input(Bool()))
    val s2_dcache_require_replay = Vec(LoadPipelineWidth, Input(Bool()))
    val s3_replay_from_fetch = Vec(LoadPipelineWidth, Input(Bool()))
    val ldout = Vec(2, DecoupledIO(new ExuOutput)) // writeback int load
    val ldRawDataOut = Vec(2, Output(new LoadDataFromLQBundle))
    val load_s1 = Vec(LoadPipelineWidth, Flipped(new PipeLoadForwardQueryIO)) // TODO: to be renamed
    val loadViolationQuery = Vec(LoadPipelineWidth, Flipped(new LoadViolationQueryIO))
    val rob = Flipped(new RobLsqIO)
    val rollback = Output(Valid(new Redirect)) // replay now starts from load instead of store
    val dcache = Flipped(ValidIO(new Refill)) // TODO: to be renamed
    val release = Flipped(ValidIO(new Release))
    val uncache = new UncacheWordIO
    val exceptionAddr = new ExceptionAddrIO
    val lqFull = Output(Bool())
    val lqCancelCnt = Output(UInt(log2Up(LoadQueueSize + 1).W))
    val trigger = Vec(LoadPipelineWidth, new LqTriggerIO)
  })

  println("LoadQueue: size:" + LoadQueueSize)

  val uop = Reg(Vec(LoadQueueSize, new MicroOp))
  // val data = Reg(Vec(LoadQueueSize, new LsRobEntry))
  val dataModule = Module(new LoadQueueDataWrapper(LoadQueueSize, wbNumRead = LoadPipelineWidth, wbNumWrite = LoadPipelineWidth))
  dataModule.io := DontCare
//  val vaddrModule = Module(new SyncDataModuleTemplate(UInt(VAddrBits.W), LoadQueueSize, numRead = LoadPipelineWidth + 1, numWrite = LoadPipelineWidth, "LqVaddr"))
//  vaddrModule.io := DontCare  //todo

  val vaddrModule = Module(new LoadQueueVaddrModule(UInt(VAddrBits.W), LoadQueueSize, numRead = LoadPipelineWidth + 1, numWrite = LoadPipelineWidth, "LqVaddr"))
  vaddrModule.io := DontCare //todo

//  val vaddrTriggerResultModule = Module(new SyncDataModuleTemplate(Vec(TriggerNum, Bool()), LoadQueueSize, numRead = LoadPipelineWidth, numWrite = LoadPipelineWidth, "LqTrigger"))
//  vaddrTriggerResultModule.io := DontCare
  val vaddrTriggerResultModule = Module(new vaddrTriggerResultDataModule(Vec(TriggerNum, Bool()), LoadQueueSize, numRead = LoadPipelineWidth, numWrite = LoadPipelineWidth, "LqTrigger"))
  vaddrTriggerResultModule.io := DontCare


  val allocated = RegInit(VecInit(List.fill(LoadQueueSize)(false.B))) // lq entry has been allocated          ///todo: no-clocking
  val datavalid = RegInit(VecInit(List.fill(LoadQueueSize)(false.B))) // data is valid                        ///todo: no-clocking
  val writebacked = RegInit(VecInit(List.fill(LoadQueueSize)(false.B))) // inst has been writebacked to CDB   ///todo: no-clocking
  val released = RegInit(VecInit(List.fill(LoadQueueSize)(false.B))) // load data has been released by dcache ///todo: no-clocking
  val error = RegInit(VecInit(List.fill(LoadQueueSize)(false.B))) // load data has been corrupted
  val miss = Reg(Vec(LoadQueueSize, Bool())) // load inst missed, waiting for miss queue to accept miss request ///todo: no-clocking
  // val listening = Reg(Vec(LoadQueueSize, Bool())) // waiting for refill result
  val pending = Reg(Vec(LoadQueueSize, Bool())) // mmio pending: inst is an mmio inst, it will not be executed until it reachs the end of rob ///todo: no-clocking
  val refilling = WireInit(VecInit(List.fill(LoadQueueSize)(false.B))) // inst has been writebacked to CDB

  val debug_mmio = Reg(Vec(LoadQueueSize, Bool())) // mmio: inst is an mmio inst
  val debug_paddr = Reg(Vec(LoadQueueSize, UInt(PAddrBits.W))) // mmio: inst is an mmio inst

  val enqPtrExt = RegInit(VecInit((0 until io.enq.req.length).map(_.U.asTypeOf(new LqPtr))))
  val deqPtrExt = RegInit(0.U.asTypeOf(new LqPtr))      ///more:todo no clock-gating
  val deqPtrExtNext = Wire(new LqPtr)

  val enqPtr = enqPtrExt(0).value
  val deqPtr = deqPtrExt.value

  val validCount = distanceBetween(enqPtrExt(0), deqPtrExt)
  val allowEnqueue = validCount <= (LoadQueueSize - 2).U

  val deqMask = UIntToMask(deqPtr, LoadQueueSize)
  val enqMask = UIntToMask(enqPtr, LoadQueueSize)

  val commitCount = RegNext(io.rob.lcommit)

  val release1cycle = io.release
  val release2cycle_valid = RegNext(io.release.valid)
  val release2cycle_paddr = RegEnable(io.release.bits.paddr, io.release.valid)
  val release2cycle_paddr_dup_lsu = RegEnable(io.release.bits.paddr, io.release.valid)

  /**
    * Enqueue at dispatch
    *
    * Currently, LoadQueue only allows enqueue when #emptyEntries > EnqWidth
    */
  io.enq.canAccept := allowEnqueue

  //for uop(index).robidx clock-gating
  val wen_robIdx = Wire(Vec(LoadQueueSize,Bool()))
  val canEnqueue = io.enq.req.map(_.valid)
  val enqCancel = io.enq.req.map(_.bits.robIdx.needFlush(io.brqRedirect))

  (0 until LoadQueueSize).foreach(i => {
    val req_valid = Wire(Vec(exuParameters.LsExuCnt,Bool()))
    (0 until exuParameters.LsExuCnt).foreach(j => {
      req_valid(j) := canEnqueue(j) && !enqCancel(j) && (io.enq.req(j).bits.lqIdx.value === i.U)
    })
    wen_robIdx(i) := req_valid.asUInt.orR
    val w_addr = OHToUInt(req_valid)
    val w_data = io.enq.req(w_addr).bits.robIdx
    when(wen_robIdx(i)){
      uop(i).robIdx := w_data
    }
  })


//  val canEnqueue = io.enq.req.map(_.valid)
//  val enqCancel = io.enq.req.map(_.bits.robIdx.needFlush(io.brqRedirect))
  for (i <- 0 until io.enq.req.length) {
    val offset = if (i == 0) 0.U else PopCount(io.enq.needAlloc.take(i))
    val lqIdx = enqPtrExt(offset)
    val index = io.enq.req(i).bits.lqIdx.value
    when (canEnqueue(i) && !enqCancel(i)) {
//      uop(index).robIdx := io.enq.req(i).bits.robIdx
      allocated(index) := true.B
      datavalid(index) := false.B
      writebacked(index) := false.B   ///more:todo
      released(index) := false.B
      miss(index) := false.B
      pending(index) := false.B
      error(index) := false.B
      XSError(!io.enq.canAccept || !io.enq.sqCanAccept, s"must accept $i\n")
      XSError(index =/= lqIdx.value, s"must be the same entry $i\n")
    }
    io.enq.resp(i) := lqIdx
  }
  XSDebug(p"(ready, valid): ${io.enq.canAccept}, ${Binary(Cat(io.enq.req.map(_.valid)))}\n")


//  val lastCycleRedirect = RegNext(io.brqRedirect)
//  val lastlastCycleRedirect = RegNext(lastCycleRedirect)

  val lastCycleRedirect_valid = RegNext(io.brqRedirect.valid)
  val lastCycleRedirect_bits = RegEnable(io.brqRedirect.bits, io.brqRedirect.valid)
  val lastlastCycleRedirect_valid = RegNext(lastCycleRedirect_valid)
  val lastlastCycleRedirect_bits = RegEnable(lastCycleRedirect_bits, lastCycleRedirect_valid)

  val lastCycleRedirect = Wire(io.brqRedirect.cloneType)
  lastCycleRedirect.valid := lastCycleRedirect_valid
  lastCycleRedirect.bits := lastCycleRedirect_bits

  val lastlastCycleRedirect = Wire(io.brqRedirect.cloneType)
  lastlastCycleRedirect.valid := lastlastCycleRedirect_valid
  lastlastCycleRedirect.bits := lastlastCycleRedirect_bits
  /**
    * Writeback load from load units
    *
    * Most load instructions writeback to regfile at the same time.
    * However,
    *   (1) For an mmio instruction with exceptions, it writes back to ROB immediately.
    *   (2) For an mmio instruction without exceptions, it does not write back.
    * The mmio instruction will be sent to lower level when it reaches ROB's head.
    * After uncache response, it will write back through arbiter with loadUnit.
    *   (3) For cache misses, it is marked miss and sent to dcache later.
    * After cache refills, it will write back through arbiter with loadUnit.
    */
  for (i <- 0 until LoadPipelineWidth) {
    dataModule.io.wb.wen(i) := false.B
    dataModule.io.paddr.wen(i) := false.B
    vaddrTriggerResultModule.io.wen(i) := false.B
    val loadWbIndex = io.loadIn(i).bits.uop.lqIdx.value

    // most lq status need to be updated immediately after load writeback to lq
    // flag bits in lq needs to be updated accurately
    when(io.loadIn(i).fire()) {
      when(io.loadIn(i).bits.miss) {
        XSInfo(io.loadIn(i).valid, "load miss write to lq idx %d pc 0x%x vaddr %x paddr %x mask %x forwardData %x forwardMask: %x mmio %x\n",
          io.loadIn(i).bits.uop.lqIdx.asUInt,
          io.loadIn(i).bits.uop.cf.pc,
          io.loadIn(i).bits.vaddr,
          io.loadIn(i).bits.paddr,
          io.loadIn(i).bits.mask,
          io.loadIn(i).bits.forwardData.asUInt,
          io.loadIn(i).bits.forwardMask.asUInt,
          io.loadIn(i).bits.mmio
        )
      }.otherwise {
        XSInfo(io.loadIn(i).valid, "load hit write to cbd lqidx %d pc 0x%x vaddr %x paddr %x mask %x forwardData %x forwardMask: %x mmio %x\n",
        io.loadIn(i).bits.uop.lqIdx.asUInt,
        io.loadIn(i).bits.uop.cf.pc,
        io.loadIn(i).bits.vaddr,
        io.loadIn(i).bits.paddr,
        io.loadIn(i).bits.mask,
        io.loadIn(i).bits.forwardData.asUInt,
        io.loadIn(i).bits.forwardMask.asUInt,
        io.loadIn(i).bits.mmio
      )}
      if(EnableFastForward){
        datavalid(loadWbIndex) := (!io.loadIn(i).bits.miss || io.s2_load_data_forwarded(i)) &&
          !io.loadIn(i).bits.mmio && // mmio data is not valid until we finished uncache access
          !io.s2_dcache_require_replay(i) // do not writeback if that inst will be resend from rs
      } else {
        datavalid(loadWbIndex) := (!io.loadIn(i).bits.miss || io.s2_load_data_forwarded(i)) &&
          !io.loadIn(i).bits.mmio // mmio data is not valid until we finished uncache access
      }
      writebacked(loadWbIndex) := !io.loadIn(i).bits.miss && !io.loadIn(i).bits.mmio

      debug_mmio(loadWbIndex) := io.loadIn(i).bits.mmio
      debug_paddr(loadWbIndex) := io.loadIn(i).bits.paddr

      val dcacheMissed = io.loadIn(i).bits.miss && !io.loadIn(i).bits.mmio
      if(EnableFastForward){
        miss(loadWbIndex) := dcacheMissed && !io.s2_load_data_forwarded(i) && !io.s2_dcache_require_replay(i)
      } else {
        miss(loadWbIndex) := dcacheMissed && !io.s2_load_data_forwarded(i)
      }
      pending(loadWbIndex) := io.loadIn(i).bits.mmio
      released(loadWbIndex) := release2cycle_valid &&
        io.loadIn(i).bits.paddr(PAddrBits-1, DCacheLineOffset) === release2cycle_paddr(PAddrBits-1, DCacheLineOffset) ||
        release1cycle.valid &&
        io.loadIn(i).bits.paddr(PAddrBits-1, DCacheLineOffset) === release1cycle.bits.paddr(PAddrBits-1, DCacheLineOffset)
    }

    // data bit in lq can be updated when load_s2 valid
    // when(io.loadIn(i).bits.lq_data_wen){
    //   val loadWbData = Wire(new LQDataEntry)
    //   loadWbData.paddr := io.loadIn(i).bits.paddr
    //   loadWbData.mask := io.loadIn(i).bits.mask
    //   loadWbData.data := io.loadIn(i).bits.forwardData.asUInt // fwd data
    //   loadWbData.fwdMask := io.loadIn(i).bits.forwardMask
    //   dataModule.io.wbWrite(i, loadWbIndex, loadWbData)
    //   dataModule.io.wb.wen(i) := true.B

    //   // dirty code for load instr
    //   uop(loadWbIndex).pdest := io.loadIn(i).bits.uop.pdest
    //   uop(loadWbIndex).cf := io.loadIn(i).bits.uop.cf
    //   uop(loadWbIndex).ctrl := io.loadIn(i).bits.uop.ctrl
    //   uop(loadWbIndex).debugInfo := io.loadIn(i).bits.uop.debugInfo

    //   vaddrTriggerResultModule.io.waddr(i) := loadWbIndex
    //   vaddrTriggerResultModule.io.wdata(i) := io.trigger(i).hitLoadAddrTriggerHitVec

    //   vaddrTriggerResultModule.io.wen(i) := true.B
    // }

    // dirty code to reduce load_s2.valid fanout
    when(io.loadIn(i).bits.lq_data_wen_dup(0)){
      val loadWbData = Wire(new LQDataEntry)
      loadWbData.paddr := io.loadIn(i).bits.paddr
      loadWbData.mask := io.loadIn(i).bits.mask
      loadWbData.data := io.loadIn(i).bits.forwardData.asUInt // fwd data
      loadWbData.fwdMask := io.loadIn(i).bits.forwardMask
      dataModule.io.wbWrite(i, loadWbIndex, loadWbData)
      dataModule.io.wb.wen(i) := true.B
    }
    // dirty code for load instr
    when(io.loadIn(i).bits.lq_data_wen_dup(1)){
      uop(loadWbIndex).pdest := io.loadIn(i).bits.uop.pdest
    }
    when(io.loadIn(i).bits.lq_data_wen_dup(2)){
      uop(loadWbIndex).cf := io.loadIn(i).bits.uop.cf
    }
    when(io.loadIn(i).bits.lq_data_wen_dup(3)){
      uop(loadWbIndex).ctrl := io.loadIn(i).bits.uop.ctrl
    }
    when(io.loadIn(i).bits.lq_data_wen_dup(4)){
      uop(loadWbIndex).debugInfo := io.loadIn(i).bits.uop.debugInfo
    }
    when(io.loadIn(i).bits.lq_data_wen_dup(5)){
      vaddrTriggerResultModule.io.waddr(i) := loadWbIndex
      vaddrTriggerResultModule.io.wdata(i) := io.trigger(i).hitLoadAddrTriggerHitVec
      vaddrTriggerResultModule.io.wen(i) := true.B
    }

    when(io.loadPaddrIn(i).valid) {
      dataModule.io.paddr.wen(i) := true.B
      dataModule.io.paddr.waddr(i) := io.loadPaddrIn(i).bits.lqIdx.value
      dataModule.io.paddr.wdata(i) := io.loadPaddrIn(i).bits.paddr
    }

    // vaddrModule write is delayed, as vaddrModule will not be read right after write
    vaddrModule.io.waddr(i) := RegEnable(loadWbIndex, io.loadIn(i).fire)
    vaddrModule.io.wdata(i) := RegEnable(io.loadIn(i).bits.vaddr, io.loadIn(i).fire)
    vaddrModule.io.wen(i) := RegNext(io.loadIn(i).fire)
  }

  when(io.dcache.valid) {
    XSDebug("miss resp: paddr:0x%x data %x\n", io.dcache.bits.addr, io.dcache.bits.data)
  }

  // Refill 64 bit in a cycle
  // Refill data comes back from io.dcache.resp
  dataModule.io.refill.valid := io.dcache.valid
  dataModule.io.refill.paddr := io.dcache.bits.addr
  dataModule.io.refill.data := io.dcache.bits.data

  val s2_dcache_require_replay = WireInit(VecInit((0 until LoadPipelineWidth).map(i =>{
    RegNext(io.loadIn(i).fire()) && RegNext(io.s2_dcache_require_replay(i))
  })))
  dontTouch(s2_dcache_require_replay)

  (0 until LoadQueueSize).map(i => {
    dataModule.io.refill.refillMask(i) := allocated(i) && miss(i)
    when(dataModule.io.refill.valid && dataModule.io.refill.refillMask(i) && dataModule.io.refill.matchMask(i)) {
      datavalid(i) := true.B
      miss(i) := false.B
      when(!s2_dcache_require_replay.asUInt.orR){
        refilling(i) := true.B
      }
      when(io.dcache.bits.error) {
        error(i) := true.B
      }
    }
  })

  for (i <- 0 until LoadPipelineWidth) {
    val loadWbIndex = io.loadIn(i).bits.uop.lqIdx.value
    val lastCycleLoadWbIndex = RegEnable(loadWbIndex,io.loadIn(i).valid)
    // update miss state in load s3
    if(!EnableFastForward){
      // s2_dcache_require_replay will be used to update lq flag 1 cycle after for better timing
      //
      // io.s2_dcache_require_replay comes from dcache miss req reject, which is quite slow to generate
      when(s2_dcache_require_replay(i)) {
        // do not writeback if that inst will be resend from rs
        // rob writeback will not be triggered by a refill before inst replay
        miss(lastCycleLoadWbIndex) := false.B // disable refill listening
        datavalid(lastCycleLoadWbIndex) := false.B // disable refill listening
        assert(!datavalid(lastCycleLoadWbIndex))
      }
    }
    // update load error state in load s3
    when(RegNext(io.loadIn(i).fire()) && io.s3_delayed_load_error(i)){
      uop(lastCycleLoadWbIndex).cf.exceptionVec(loadAccessFault) := true.B
    }
    // update inst replay from fetch flag in s3
    when(RegNext(io.loadIn(i).fire()) && io.s3_replay_from_fetch(i)){
      uop(lastCycleLoadWbIndex).ctrl.replayInst := true.B         ///todo: no clock-gating
    }
  }


  // Writeback up to 2 missed load insts to CDB
  //
  // Pick 2 missed load (data refilled), write them back to cdb
  // 2 refilled load will be selected from even/odd entry, separately

  // Stage 0
  // Generate writeback indexes

  def getEvenBits(input: UInt): UInt = {
    VecInit((0 until LoadQueueSize/2).map(i => {input(2*i)})).asUInt
  }
  def getOddBits(input: UInt): UInt = {
    VecInit((0 until LoadQueueSize/2).map(i => {input(2*i+1)})).asUInt
  }

  val loadWbSel = Wire(Vec(LoadPipelineWidth, UInt(log2Up(LoadQueueSize).W))) // index selected last cycle
  val loadWbSelV = Wire(Vec(LoadPipelineWidth, Bool())) // index selected in last cycle is valid

  val loadWbSelVec = VecInit((0 until LoadQueueSize).map(i => {
    // allocated(i) && !writebacked(i) && (datavalid(i) || refilling(i))
    allocated(i) && !writebacked(i) && datavalid(i) // query refilling will cause bad timing
  })).asUInt() // use uint instead vec to reduce verilog lines
  val evenDeqMask = getEvenBits(deqMask)
  val oddDeqMask = getOddBits(deqMask)
  // generate lastCycleSelect mask
  val evenFireMask = getEvenBits(UIntToOH(loadWbSel(0)))
  val oddFireMask = getOddBits(UIntToOH(loadWbSel(1)))
  // generate real select vec
  def toVec(a: UInt): Vec[Bool] = {
    VecInit(a.asBools)
  }
  val loadEvenSelVecFire = getEvenBits(loadWbSelVec) & ~evenFireMask
  val loadOddSelVecFire = getOddBits(loadWbSelVec) & ~oddFireMask
  val loadEvenSelVecNotFire = getEvenBits(loadWbSelVec)
  val loadOddSelVecNotFire = getOddBits(loadWbSelVec)
//  val loadEvenSel = Mux(
//    io.ldout(0).fire(),
//    getFirstOne(toVec(loadEvenSelVecFire), evenDeqMask),
//    getFirstOne(toVec(loadEvenSelVecNotFire), evenDeqMask)
//  )
//  val loadOddSel= Mux(
//    io.ldout(1).fire(),
//    getFirstOne(toVec(loadOddSelVecFire), oddDeqMask),
//    getFirstOne(toVec(loadOddSelVecNotFire), oddDeqMask)
//  )

  val loadEvenSel = Mux(
    io.ldout(0).fire(),
    PriorityEncoder(toVec(loadEvenSelVecFire)),
    PriorityEncoder(toVec(loadEvenSelVecNotFire))
  )
  val loadOddSel = Mux(
    io.ldout(1).fire(),
    PriorityEncoder(toVec(loadOddSelVecFire)),
    PriorityEncoder(toVec(loadOddSelVecNotFire))
  )


  val loadWbSelGen = Wire(Vec(LoadPipelineWidth, UInt(log2Up(LoadQueueSize).W)))
  val loadWbSelVGen = Wire(Vec(LoadPipelineWidth, Bool()))
  loadWbSelGen(0) := Cat(loadEvenSel, 0.U(1.W))
  loadWbSelVGen(0):= Mux(io.ldout(0).fire(), loadEvenSelVecFire.asUInt.orR, loadEvenSelVecNotFire.asUInt.orR)
  loadWbSelGen(1) := Cat(loadOddSel, 1.U(1.W))
  loadWbSelVGen(1) := Mux(io.ldout(1).fire(), loadOddSelVecFire.asUInt.orR, loadOddSelVecNotFire.asUInt.orR)

  (0 until LoadPipelineWidth).map(i => {
    loadWbSel(i) := RegNext(loadWbSelGen(i))
    loadWbSelV(i) := RegNext(loadWbSelVGen(i), init = false.B)
    when(io.ldout(i).fire()){
      // Mark them as writebacked, so they will not be selected in the next cycle
      writebacked(loadWbSel(i)) := true.B
    }
  })

  // Stage 1
  // Use indexes generated in cycle 0 to read data
  // writeback data to cdb
  (0 until LoadPipelineWidth).map(i => {
    // data select
//    dataModule.io.wb.raddr(i) := loadWbSelGen(i)
    dataModule.io.wb.raddr(i) := loadWbSel(i)
    val rdata = dataModule.io.wb.rdata(i).data
    val seluop = uop(loadWbSel(i))
    val func = seluop.ctrl.fuOpType
    val raddr = dataModule.io.wb.rdata(i).paddr
    val rdataSel = LookupTree(raddr(2, 0), List(
      "b000".U -> rdata(63, 0),
      "b001".U -> rdata(63, 8),
      "b010".U -> rdata(63, 16),
      "b011".U -> rdata(63, 24),
      "b100".U -> rdata(63, 32),
      "b101".U -> rdata(63, 40),
      "b110".U -> rdata(63, 48),
      "b111".U -> rdata(63, 56)
    ))
    val rdataPartialLoad = rdataHelper(seluop, rdataSel)

    // writeback missed int/fp load
    //
    // Int load writeback will finish (if not blocked) in one cycle
    io.ldout(i).bits.uop := seluop
    io.ldout(i).bits.uop.lqIdx := loadWbSel(i).asTypeOf(new LqPtr)
    io.ldout(i).bits.data := rdataPartialLoad // not used
    io.ldout(i).bits.redirectValid := false.B
    io.ldout(i).bits.redirect := DontCare
    io.ldout(i).bits.debug.isMMIO := debug_mmio(loadWbSel(i))
    io.ldout(i).bits.debug.isPerfCnt := false.B
    io.ldout(i).bits.debug.paddr := debug_paddr(loadWbSel(i))
    io.ldout(i).bits.debug.vaddr := vaddrModule.io.rdata(i+1)
    io.ldout(i).bits.fflags := DontCare
    io.ldout(i).valid := loadWbSelV(i) && !io.ldout(i).bits.uop.robIdx.needFlush(lastCycleRedirect)

    // merged data, uop and offset for data sel in load_s3
    io.ldRawDataOut(i).lqData := dataModule.io.wb.rdata(i).data
    io.ldRawDataOut(i).uop := io.ldout(i).bits.uop
    io.ldRawDataOut(i).addrOffset := dataModule.io.wb.rdata(i).paddr

    when(io.ldout(i).fire()) {
      XSInfo("int load miss write to cbd robidx %d lqidx %d pc 0x%x mmio %x\n",
        io.ldout(i).bits.uop.robIdx.asUInt,
        io.ldout(i).bits.uop.lqIdx.asUInt,
        io.ldout(i).bits.uop.cf.pc,
        debug_mmio(loadWbSel(i))
      )
    }

  })

  /**
    * Load commits
    *
    * When load commited, mark it as !allocated and move deqPtrExt forward.
    */
  (0 until CommitWidth).map(i => {
    when(commitCount > i.U){
      allocated((deqPtrExt+i.U).value) := false.B
      XSError(!allocated((deqPtrExt+i.U).value), s"why commit invalid entry $i?\n")
    }
  })

  def getFirstOne(mask: Vec[Bool], startMask: UInt) = {
    val length = mask.length
    val highBits = (0 until length).map(i => mask(i) & ~startMask(i))
    val highBitsUint = Cat(highBits.reverse)
    PriorityEncoder(Mux(highBitsUint.orR(), highBitsUint, mask.asUInt))
  }

  def getOldestInTwo(valid: Seq[Bool], uop: Seq[MicroOp]) = {
    assert(valid.length == uop.length)
    assert(valid.length == 2)
    Mux(valid(0) && valid(1),
      Mux(isAfter(uop(0).robIdx, uop(1).robIdx), uop(1), uop(0)),
      Mux(valid(0) && !valid(1), uop(0), uop(1)))
  }

  def getAfterMask(valid: Seq[Bool], uop: Seq[MicroOp]) = {
    assert(valid.length == uop.length)
    val length = valid.length
    (0 until length).map(i => {
      (0 until length).map(j => {
        Mux(valid(i) && valid(j),
          isAfter(uop(i).robIdx, uop(j).robIdx),
          Mux(!valid(i), true.B, false.B))
      })
    })
  }

  /**
    * Store-Load Memory violation detection
    *
    * When store writes back, it searches LoadQueue for younger load instructions
    * with the same load physical address. They loaded wrong data and need re-execution.
    *
    * Cycle 0: Store Writeback
    *   Generate match vector for store address with rangeMask(stPtr, enqPtr).
    *   Besides, load instructions in LoadUnit_S1 and S2 are also checked.
    * Cycle 1: Redirect Generation
    *   There're three possible types of violations, up to 6 possible redirect requests.
    *   Choose the oldest load (part 1). (4 + 2) -> (1 + 2)
    * Cycle 2: Redirect Fire
    *   Choose the oldest load (part 2). (3 -> 1)
    *   Prepare redirect request according to the detected violation.
    *   Fire redirect request (if valid)
    */

  // stage 0:        lq l1 wb     l1 wb lq
  //                 |  |  |      |  |  |  (paddr match)
  // stage 1:        lq l1 wb     l1 wb lq
  //                 |  |  |      |  |  |
  //                 |  |------------|  |
  //                 |        |         |
  // stage 2:        lq      l1wb       lq
  //                 |        |         |
  //                 --------------------
  //                          |
  //                      rollback req
  io.load_s1 := DontCare
  def detectRollback(i: Int) = {
    val startIndex = io.storeIn(i).bits.uop.lqIdx.value
    val lqIdxMask = UIntToMask(startIndex, LoadQueueSize)
    val xorMask = lqIdxMask ^ enqMask
    val sameFlag = io.storeIn(i).bits.uop.lqIdx.flag === enqPtrExt(0).flag
    val stToEnqPtrMask = Mux(sameFlag, xorMask, ~xorMask)

    // check if load already in lq needs to be rolledback
    dataModule.io.violation(i).paddr := io.storeIn(i).bits.paddr
    dataModule.io.violation(i).mask := io.storeIn(i).bits.mask
    val addrMaskMatch = RegEnable(dataModule.io.violation(i).violationMask,io.storeIn(i).valid)
    val entryNeedCheck = VecInit((0 until LoadQueueSize).map(j => {
      RegEnable(allocated(j) && stToEnqPtrMask(j) && (datavalid(j) || miss(j)),io.storeIn(i).valid)
    }))

//    val entryNeedCheck = RegNext(VecInit((0 until LoadQueueSize).map(j => {     ///todo:more
//      allocated(j) && stToEnqPtrMask(j) && (datavalid(j) || miss(j))
//    })))
    val lqViolationVec = VecInit((0 until LoadQueueSize).map(j => {
      addrMaskMatch(j) && entryNeedCheck(j)
    }))
    val lqViolation = lqViolationVec.asUInt().orR() && RegEnable(!io.storeIn(i).bits.miss,io.storeIn(i).valid)
    val lqViolationIndex = getFirstOne(lqViolationVec, RegEnable(lqIdxMask,io.storeIn(i).valid))
    val lqViolationUop = uop(lqViolationIndex)
    // lqViolationUop.lqIdx.flag := deqMask(lqViolationIndex) ^ deqPtrExt.flag
    // lqViolationUop.lqIdx.value := lqViolationIndex
    XSDebug(lqViolation, p"${Binary(Cat(lqViolationVec))}, $startIndex, $lqViolationIndex\n")

    // when l/s writeback to rob together, check if rollback is needed
    val wbViolationVec = RegNext(VecInit((0 until LoadPipelineWidth).map(j => {
      io.loadIn(j).valid &&
      isAfter(io.loadIn(j).bits.uop.robIdx, io.storeIn(i).bits.uop.robIdx) &&
      io.storeIn(i).bits.paddr(PAddrBits - 1, 3) === io.loadIn(j).bits.paddr(PAddrBits - 1, 3) &&
      (io.storeIn(i).bits.mask & io.loadIn(j).bits.mask).orR
    })))
    val wbViolation = wbViolationVec.asUInt().orR() && RegNext(io.storeIn(i).valid && !io.storeIn(i).bits.miss)
    val wbUopNext = VecInit(io.loadIn.map(in => {
      RegEnable(in.bits.uop,in.valid)
    }))
    val wbViolationUop = getOldestInTwo(wbViolationVec, wbUopNext)
    XSDebug(wbViolation, p"${Binary(Cat(wbViolationVec))}, $wbViolationUop\n")

    // check if rollback is needed for load in l1
    val l1ViolationVec = RegNext(VecInit((0 until LoadPipelineWidth).map(j => {
      io.load_s1(j).valid && // L1 valid
      isAfter(io.load_s1(j).uop.robIdx, io.storeIn(i).bits.uop.robIdx) &&
      io.storeIn(i).bits.paddr(PAddrBits - 1, 3) === io.load_s1(j).paddr(PAddrBits - 1, 3) &&
      (io.storeIn(i).bits.mask & io.load_s1(j).mask).orR
    })))
    val l1Violation = l1ViolationVec.asUInt().orR() && RegNext(io.storeIn(i).valid && !io.storeIn(i).bits.miss)
    val l1UopNext = VecInit(io.load_s1.map(in => {
      RegEnable(in.uop,in.valid)
    }))
    val l1ViolationUop = getOldestInTwo(l1ViolationVec, l1UopNext)
    XSDebug(l1Violation, p"${Binary(Cat(l1ViolationVec))}, $l1ViolationUop\n")

    XSDebug(
      l1Violation,
      "need rollback (l1 load) pc %x robidx %d target %x\n",
      io.storeIn(i).bits.uop.cf.pc, io.storeIn(i).bits.uop.robIdx.asUInt, l1ViolationUop.robIdx.asUInt
    )
    XSDebug(
      lqViolation,
      "need rollback (ld wb before store) pc %x robidx %d target %x\n",
      io.storeIn(i).bits.uop.cf.pc, io.storeIn(i).bits.uop.robIdx.asUInt, lqViolationUop.robIdx.asUInt
    )
    XSDebug(
      wbViolation,
      "need rollback (ld/st wb together) pc %x robidx %d target %x\n",
      io.storeIn(i).bits.uop.cf.pc, io.storeIn(i).bits.uop.robIdx.asUInt, wbViolationUop.robIdx.asUInt
    )

    ((lqViolation, lqViolationUop), (wbViolation, wbViolationUop), (l1Violation, l1ViolationUop))
  }

  def rollbackSel(a: Valid[MicroOpRbExt], b: Valid[MicroOpRbExt]): ValidIO[MicroOpRbExt] = {
    Mux(
      a.valid,
      Mux(
        b.valid,
        Mux(isAfter(a.bits.uop.robIdx, b.bits.uop.robIdx), b, a), // a,b both valid, sel oldest
        a // sel a
      ),
      b // sel b
    )
  }

  // S2: select rollback (part1) and generate rollback request
  // rollback check
  // Wb/L1 rollback seq check is done in s2
  val rollbackWb = Wire(Vec(StorePipelineWidth, Valid(new MicroOpRbExt)))
  val rollbackL1 = Wire(Vec(StorePipelineWidth, Valid(new MicroOpRbExt)))
  val rollbackL1Wb = Wire(Vec(StorePipelineWidth*2, Valid(new MicroOpRbExt)))
  // Lq rollback seq check is done in s3 (next stage), as getting rollbackLq MicroOp is slow
  val rollbackLq = Wire(Vec(StorePipelineWidth, Valid(new MicroOpRbExt)))
  // store ftq index for store set update
  val stFtqIdxS2 = Wire(Vec(StorePipelineWidth, new FtqPtr))
  val stFtqOffsetS2 = Wire(Vec(StorePipelineWidth, UInt(log2Up(PredictWidth).W)))
  for (i <- 0 until StorePipelineWidth) {
    val detectedRollback = detectRollback(i)
    rollbackLq(i).valid := detectedRollback._1._1 && RegNext(io.storeIn(i).valid)
    rollbackLq(i).bits.uop := detectedRollback._1._2
    rollbackLq(i).bits.flag := i.U
    rollbackWb(i).valid := detectedRollback._2._1 && RegNext(io.storeIn(i).valid)
    rollbackWb(i).bits.uop := detectedRollback._2._2
    rollbackWb(i).bits.flag := i.U
    rollbackL1(i).valid := detectedRollback._3._1 && RegNext(io.storeIn(i).valid)
    rollbackL1(i).bits.uop := detectedRollback._3._2
    rollbackL1(i).bits.flag := i.U
    rollbackL1Wb(2*i) := rollbackL1(i)
    rollbackL1Wb(2*i+1) := rollbackWb(i)
//    stFtqIdxS2(i) := RegNext(io.storeIn(i).bits.uop.cf.ftqPtr)
//    stFtqOffsetS2(i) := RegNext(io.storeIn(i).bits.uop.cf.ftqOffset)
    stFtqIdxS2(i) := RegEnable(io.storeIn(i).bits.uop.cf.ftqPtr,io.storeIn(i).valid)
    stFtqOffsetS2(i) := RegEnable(io.storeIn(i).bits.uop.cf.ftqOffset,io.storeIn(i).valid)
  }

  val rollbackL1WbSelected = ParallelOperation(rollbackL1Wb, rollbackSel)
  val rollbackL1WbVReg = RegNext(rollbackL1WbSelected.valid)
  val rollbackL1WbReg = RegEnable(rollbackL1WbSelected.bits, rollbackL1WbSelected.valid)
  val rollbackLq0VReg = RegNext(rollbackLq(0).valid)
  val rollbackLq0Reg = RegEnable(rollbackLq(0).bits, rollbackLq(0).valid)
  val rollbackLq1VReg = RegNext(rollbackLq(1).valid)
  val rollbackLq1Reg = RegEnable(rollbackLq(1).bits, rollbackLq(1).valid)

  // S3: select rollback (part2), generate rollback request, then fire rollback request
  // Note that we use robIdx - 1.U to flush the load instruction itself.
  // Thus, here if last cycle's robIdx equals to this cycle's robIdx, it still triggers the redirect.

  // FIXME: this is ugly
  val rollbackValidVec = Seq(rollbackL1WbVReg, rollbackLq0VReg, rollbackLq1VReg)
  val rollbackUopExtVec = Seq(rollbackL1WbReg, rollbackLq0Reg, rollbackLq1Reg)

  // select uop in parallel
  val mask = getAfterMask(rollbackValidVec, rollbackUopExtVec.map(i => i.uop))
  val oneAfterZero = mask(1)(0)
  val rollbackUopExt = Mux(oneAfterZero && mask(2)(0),
    rollbackUopExtVec(0),
    Mux(!oneAfterZero && mask(2)(1), rollbackUopExtVec(1), rollbackUopExtVec(2)))

  val storeInValidNext = io.storeIn.map(in => RegNext(in.valid))
  val stFtqIdxS3 = VecInit(storeInValidNext.zip(stFtqIdxS2).map({ case (v, d) => {
    RegEnable(d, v)
  }}))
  val stFtqOffsetS3 = VecInit(storeInValidNext.zip(stFtqOffsetS2).map({ case (v, d) => {
    RegEnable(d, v)
  }}))

  //  val stFtqIdxS3 = RegNext(stFtqIdxS2)                //more:todo
  //  val stFtqOffsetS3 = RegNext(stFtqOffsetS2)          //more:todo
  val rollbackUop = rollbackUopExt.uop
  val rollbackStFtqIdx = stFtqIdxS3(rollbackUopExt.flag)
  val rollbackStFtqOffset = stFtqOffsetS3(rollbackUopExt.flag)

  // check if rollback request is still valid in parallel
  val rollbackValidVecChecked = Wire(Vec(3, Bool()))
  for(((v, uop), idx) <- rollbackValidVec.zip(rollbackUopExtVec.map(i => i.uop)).zipWithIndex) {
    rollbackValidVecChecked(idx) := v &&
      (!lastCycleRedirect.valid || isBefore(uop.robIdx, lastCycleRedirect.bits.robIdx)) &&
      (!lastlastCycleRedirect.valid || isBefore(uop.robIdx, lastlastCycleRedirect.bits.robIdx))
  }

  io.rollback.bits.robIdx := rollbackUop.robIdx
  io.rollback.bits.ftqIdx := rollbackUop.cf.ftqPtr
  io.rollback.bits.stFtqIdx := rollbackStFtqIdx
  io.rollback.bits.ftqOffset := rollbackUop.cf.ftqOffset
  io.rollback.bits.stFtqOffset := rollbackStFtqOffset
  io.rollback.bits.level := RedirectLevel.flush
  io.rollback.bits.interrupt := false.B
  io.rollback.bits.cfiUpdate := DontCare
  io.rollback.bits.cfiUpdate.target := rollbackUop.cf.pc
  io.rollback.bits.isException := false.B
  io.rollback.bits.isLoadStore := true.B
  io.rollback.bits.isLoadLoad := false.B
  io.rollback.bits.isXRet := false.B
  io.rollback.bits.isFlushPipe := false.B
  // io.rollback.bits.pc := DontCare

  io.rollback.valid := rollbackValidVecChecked.asUInt.orR

  when(io.rollback.valid) {
    // XSDebug("Mem rollback: pc %x robidx %d\n", io.rollback.bits.cfi, io.rollback.bits.robIdx.asUInt)
  }

  /**
  * Load-Load Memory violation detection
  *
  * When load arrives load_s1, it searches LoadQueue for younger load instructions
  * with the same load physical address. If younger load has been released (or observed),
  * the younger load needs to be re-execed.
  *
  * For now, if re-exec it found to be needed in load_s1, we mark the older load as replayInst,
  * the two loads will be replayed if the older load becomes the head of rob.
  *
  * When dcache releases a line, mark all writebacked entrys in load queue with
  * the same line paddr as released.
  */

  // Load-Load Memory violation query
  val deqRightMask = UIntToMask.rightmask(deqPtr, LoadQueueSize)
  (0 until LoadPipelineWidth).map(i => {
    val ldldViolationReqValid = io.loadViolationQuery(i).req.valid
    dataModule.io.release_violation(i).paddr := io.loadViolationQuery(i).req.bits.paddr
    io.loadViolationQuery(i).req.ready := true.B
    io.loadViolationQuery(i).resp.valid := RegNext(io.loadViolationQuery(i).req.fire())
    // Generate real violation mask
    // Note that we use UIntToMask.rightmask here
    val startIndex = io.loadViolationQuery(i).req.bits.uop.lqIdx.value
    val lqIdxMask = UIntToMask(startIndex, LoadQueueSize)
    val xorMask = lqIdxMask ^ enqMask
    val sameFlag = io.loadViolationQuery(i).req.bits.uop.lqIdx.flag === enqPtrExt(0).flag
    val ldToEnqPtrMask = Mux(sameFlag, xorMask, ~xorMask)
    val ldld_violation_mask_gen_1 = WireInit(VecInit((0 until LoadQueueSize).map(j => {
      ldToEnqPtrMask(j) && // the load is younger than current load
      allocated(j) && // entry is valid
      released(j) && // cacheline is released
      (datavalid(j) || miss(j)) // paddr is valid
    })))
    val ldld_violation_mask_gen_2 = WireInit(VecInit((0 until LoadQueueSize).map(j => {
      dataModule.io.release_violation(i).match_mask(j)// addr match
      // addr match result is slow to generate, we RegNext() it
    })))
//    val ldld_violation_mask = RegNext(ldld_violation_mask_gen_1).asUInt & RegNext(ldld_violation_mask_gen_2).asUInt
    val ldld_violation_mask = RegEnable(ldld_violation_mask_gen_1,ldldViolationReqValid).asUInt & RegEnable(ldld_violation_mask_gen_2,ldldViolationReqValid).asUInt
    dontTouch(ldld_violation_mask)
    ldld_violation_mask.suggestName("ldldViolationMask_" + i)
    io.loadViolationQuery(i).resp.bits.have_violation := ldld_violation_mask.orR
  })

  // "released" flag update
  //
  // When io.release.valid (release1cycle.valid), it uses the last ld-ld paddr cam port to
  // update release flag in 1 cycle

  when(release1cycle.valid){
    // Take over ld-ld paddr cam port
    dataModule.io.release_violation.takeRight(1)(0).paddr := release1cycle.bits.paddr
    io.loadViolationQuery.takeRight(1)(0).req.ready := false.B
  }

  when(release2cycle_valid){
    // If a load comes in that cycle, we can not judge if it has ld-ld violation
    // We replay that load inst from RS
    io.loadViolationQuery.map(i => i.req.ready :=
      // use lsu side release2cycle_dup_lsu paddr for better timing
      !(i.req.bits.paddr(PAddrBits-1, DCacheLineOffset) === release2cycle_paddr_dup_lsu(PAddrBits-1, DCacheLineOffset))
    )
    // io.loadViolationQuery.map(i => i.req.ready := false.B) // For better timing
  }

  (0 until LoadQueueSize).map(i => {
    when(RegNext(dataModule.io.release_violation.takeRight(1)(0).match_mask(i) &&
      allocated(i) &&
      datavalid(i) &&
      release1cycle.valid
    )){
      // Note: if a load has missed in dcache and is waiting for refill in load queue,
      // its released flag still needs to be set as true if addr matches.
      released(i) := true.B
    }
  })

  /**
    * Memory mapped IO / other uncached operations
    *
    * States:
    * (1) writeback from store units: mark as pending
    * (2) when they reach ROB's head, they can be sent to uncache channel
    * (3) response from uncache channel: mark as datavalid
    * (4) writeback to ROB (and other units): mark as writebacked
    * (5) ROB commits the instruction: same as normal instructions
    */
  //(2) when they reach ROB's head, they can be sent to uncache channel
  dataModule.io.uncache.ren := false.B

  val lqTailMmioPending = WireInit(pending(deqPtr))
  val lqTailAllocated = WireInit(allocated(deqPtr))
  val s_idle :: s_req :: s_resp :: s_wait :: Nil = Enum(4)
  val uncacheState = RegInit(s_idle)
  switch(uncacheState) {
    is(s_idle) {
      when(RegNext(io.rob.pendingld && lqTailMmioPending && lqTailAllocated)) {
        dataModule.io.uncache.ren := true.B
        uncacheState := s_req
      }
    }
    is(s_req) {
      when(io.uncache.req.fire()) {
        uncacheState := s_resp
      }
    }
    is(s_resp) {
      when(io.uncache.resp.fire()) {
        uncacheState := s_wait
      }
    }
    is(s_wait) {
      when(RegNext(io.rob.commit)) {
        uncacheState := s_idle // ready for next mmio
      }
    }
  }
  io.uncache.req.valid := uncacheState === s_req

  dataModule.io.uncache.raddr := deqPtrExtNext.value  //todo

  io.uncache.req.bits.cmd  := MemoryOpConstants.M_XRD
  io.uncache.req.bits.addr := dataModule.io.uncache.rdata.paddr
  io.uncache.req.bits.data := dataModule.io.uncache.rdata.data
  io.uncache.req.bits.mask := dataModule.io.uncache.rdata.mask
  io.uncache.req.bits.robIdx := DontCare
  io.uncache.req.bits.id   := DontCare
  io.uncache.req.bits.instrtype := DontCare

  io.uncache.resp.ready := true.B

  when (io.uncache.req.fire()) {
    pending(deqPtr) := false.B

    XSDebug("uncache req: pc %x addr %x data %x op %x mask %x\n",
      uop(deqPtr).cf.pc,
      io.uncache.req.bits.addr,
      io.uncache.req.bits.data,
      io.uncache.req.bits.cmd,
      io.uncache.req.bits.mask
    )
  }

  // (3) response from uncache channel: mark as datavalid
  dataModule.io.uncache.wen := false.B
  when(io.uncache.resp.fire()){
    datavalid(deqPtr) := true.B
    dataModule.io.uncacheWrite(deqPtr, io.uncache.resp.bits.data(XLEN-1, 0))
    dataModule.io.uncache.wen := true.B

    XSDebug("uncache resp: data %x\n", io.dcache.bits.data)
  }

  // Read vaddr for mem exception
  // no inst will be commited 1 cycle before tval update
  vaddrModule.io.raddr(0) := RegNext((deqPtrExt + commitCount).value)
  io.exceptionAddr.vaddr := vaddrModule.io.rdata(0)

  // Read vaddr for debug
  (0 until LoadPipelineWidth).map(i => {
    vaddrModule.io.raddr(i+1) := RegNext(loadWbSel(i))
  })

  (0 until LoadPipelineWidth).map(i => {
//    vaddrTriggerResultModule.io.raddr(i) := loadWbSelGen(i)
    vaddrTriggerResultModule.io.raddr(i) := loadWbSel(i)
    io.trigger(i).lqLoadAddrTriggerHitVec := Mux(
      loadWbSelV(i),
      vaddrTriggerResultModule.io.rdata(i),
      VecInit(Seq.fill(TriggerNum)(false.B))
    )
  })

  // misprediction recovery / exception redirect
  // invalidate lq term using robIdx
  val needCancel = Wire(Vec(LoadQueueSize, Bool()))
  for (i <- 0 until LoadQueueSize) {
    needCancel(i) := uop(i).robIdx.needFlush(io.brqRedirect) && allocated(i)
    when (needCancel(i)) {
      allocated(i) := false.B
    }
  }


  val splitNum = 4
  val needCancelCntSub = Wire(Vec(splitNum,UInt(log2Up(LoadQueueSize).W)))
  (0 until splitNum).foreach({case i => {
    needCancelCntSub(i) := PopCount(needCancel.asUInt((i + 1) * (LoadQueueSize/splitNum) - 1, i * (LoadQueueSize/splitNum)))
  }})
  val needCancelCnt = needCancelCntSub.reduce(_+_)
  val needCancelReg = RegInit(0.U(LoadQueueSize.W))
  needCancelReg := needCancelCnt
  val lastCycleCancelCount = needCancelReg


  /**
    * update pointers
    */
  val lastEnqCancel = PopCount(RegNext(VecInit(canEnqueue.zip(enqCancel).map(x => x._1 && x._2))))
  val enqNumber = Mux(io.enq.canAccept && io.enq.sqCanAccept, PopCount(io.enq.req.map(_.valid)), 0.U)
  val enqNumber_enq = Mux(io.enq.canAccept && io.enq.sqCanAccept, io.enq.reqNum, 0.U)

  XSError(enqNumber =/= enqNumber_enq,"enqNumber and enqNumber_enq must be equal\n")

  when (lastCycleRedirect.valid) {
    // we recover the pointers in the next cycle after redirect
    enqPtrExt := VecInit(enqPtrExt.map(_ - (lastCycleCancelCount + lastEnqCancel)))
  }.otherwise {
    enqPtrExt := VecInit(enqPtrExt.map(_ + enqNumber_enq))
  }

  deqPtrExtNext := deqPtrExt + commitCount
  deqPtrExt := deqPtrExtNext

  io.lqCancelCnt := RegNext(lastCycleCancelCount + lastEnqCancel)   ///todo:no clock-gating

  /**
    * misc
    */
  // perf counter
  QueuePerf(LoadQueueSize, validCount, !allowEnqueue)
  io.lqFull := !allowEnqueue
  XSPerfAccumulate("rollback", io.rollback.valid) // rollback redirect generated
  XSPerfAccumulate("mmioCycle", uncacheState =/= s_idle) // lq is busy dealing with uncache req
  XSPerfAccumulate("mmioCnt", io.uncache.req.fire())
  XSPerfAccumulate("refill", io.dcache.valid)
  XSPerfAccumulate("writeback_success", PopCount(VecInit(io.ldout.map(i => i.fire()))))
  XSPerfAccumulate("writeback_blocked", PopCount(VecInit(io.ldout.map(i => i.valid && !i.ready))))
  XSPerfAccumulate("utilization_miss", PopCount((0 until LoadQueueSize).map(i => allocated(i) && miss(i))))

  if (env.EnableTopDown) {
    val stall_loads_bound = WireDefault(0.B)
    ExcitingUtils.addSink(stall_loads_bound, "stall_loads_bound", ExcitingUtils.Perf)
    val have_miss_entry = (allocated zip miss).map(x => x._1 && x._2).reduce(_ || _)
    val l1d_loads_bound = stall_loads_bound && !have_miss_entry
    ExcitingUtils.addSource(l1d_loads_bound, "l1d_loads_bound", ExcitingUtils.Perf)
    XSPerfAccumulate("l1d_loads_bound", l1d_loads_bound)
    val stall_l1d_load_miss = stall_loads_bound && have_miss_entry
    ExcitingUtils.addSource(stall_l1d_load_miss, "stall_l1d_load_miss", ExcitingUtils.Perf)
    ExcitingUtils.addSink(WireInit(0.U), "stall_l1d_load_miss", ExcitingUtils.Perf)
  }
  
  val perfValidCount = RegNext(validCount)

  val perfEvents = Seq(
    ("rollback         ", io.rollback.valid),
    ("mmioCycle        ", uncacheState =/= s_idle),
    ("mmio_Cnt         ", io.uncache.req.fire()),
    ("refill           ", io.dcache.valid),
    ("writeback_success", PopCount(VecInit(io.ldout.map(i => i.fire())))),
    ("writeback_blocked", PopCount(VecInit(io.ldout.map(i => i.valid && !i.ready)))),
    ("ltq_1_4_valid    ", (perfValidCount < (LoadQueueSize.U/4.U))),
    ("ltq_2_4_valid    ", (perfValidCount > (LoadQueueSize.U/4.U)) & (perfValidCount <= (LoadQueueSize.U/2.U))),
    ("ltq_3_4_valid    ", (perfValidCount > (LoadQueueSize.U/2.U)) & (perfValidCount <= (LoadQueueSize.U*3.U/4.U))),
    ("ltq_4_4_valid    ", (perfValidCount > (LoadQueueSize.U*3.U/4.U)))
  )
  generatePerfEvent()

  // debug info
  XSDebug("enqPtrExt %d:%d deqPtrExt %d:%d\n", enqPtrExt(0).flag, enqPtr, deqPtrExt.flag, deqPtr)

  def PrintFlag(flag: Bool, name: String): Unit = {
    when(flag) {
      XSDebug(false, true.B, name)
    }.otherwise {
      XSDebug(false, true.B, " ")
    }
  }

  for (i <- 0 until LoadQueueSize) {
    XSDebug(i + " pc %x pa %x ", uop(i).cf.pc, debug_paddr(i))
    PrintFlag(allocated(i), "a")
    PrintFlag(allocated(i) && datavalid(i), "v")
    PrintFlag(allocated(i) && writebacked(i), "w")
    PrintFlag(allocated(i) && miss(i), "m")
    PrintFlag(allocated(i) && pending(i), "p")
    XSDebug(false, true.B, "\n")
  }

}
