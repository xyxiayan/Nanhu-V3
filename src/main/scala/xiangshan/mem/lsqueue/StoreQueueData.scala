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
import utils._
import xs.utils._
import xiangshan._
import xiangshan.cache._

class SQVPAddrModule( CommonNumRead : Int, CommonNumWrite : Int, numEntries : Int, NumForward : Int,
                      PAddrWidth: Int, PNumRead: Int, PNumWrite:Int,
                      VAddrWidth: Int, VNumRead: Int,VNumWrite: Int)
                    (implicit p: Parameters) extends XSModule with HasDCacheParameters{
  //specialized optimization
  require((CommonNumRead == 2) && (CommonNumWrite == 2) &&
    (PNumRead == 0) && (VNumRead == 1) &&
    (PNumWrite == 0) && (VNumWrite == 0))

  val io = IO(new Bundle(){
    // sync read
    val raddr = Input(Vec(CommonNumRead,UInt(log2Up(numEntries).W)))
    val raddr_ext_v = Input(Vec(VNumRead,UInt(log2Up(numEntries).W)))

    val rdata_p = Output(Vec(CommonNumRead, UInt(PAddrWidth.W)))
    val rdata_v = Output(Vec(CommonNumRead, UInt(VAddrWidth.W)))
    val rdata_ext_v = Output(Vec(VNumRead, UInt(VAddrWidth.W)))

    val rlineflag_v_p = Output(Vec(CommonNumRead, Bool()))
    //write
    val wen = Input(Vec(CommonNumWrite, Bool()))
    val waddr = Input(Vec(CommonNumWrite, UInt(log2Up(numEntries).W)))
    val wdata_v = Input(Vec(CommonNumWrite, UInt(VAddrWidth.W)))
    val wdata_p = Input(Vec(CommonNumWrite, UInt(PAddrWidth.W)))
    val wlineflag = Input(Vec(CommonNumWrite, Bool()))

    // forward addr cam for paddr
    val forwardMdata_p = Input(Vec(NumForward, UInt(PAddrWidth.W)))
    val forwardMmask_p = Output(Vec(NumForward, Vec(numEntries, Bool())))
    // forward addr cam for vaddr
    val forwardMdata_v = Input(Vec(NumForward, UInt(VAddrWidth.W)))
    val forwardMmask_v = Output(Vec(NumForward, Vec(numEntries, Bool())))

    //for debug
    val debug_data_v = Output(Vec(numEntries, UInt(VAddrWidth.W)))
    val debug_data_p = Output(Vec(numEntries, UInt(PAddrWidth.W)))
  })


  val offset = 12
  val data_vaddr = Reg(Vec(numEntries, UInt((VAddrWidth - offset).W)))
  val data_paddr = Reg(Vec(numEntries, UInt((PAddrWidth - offset).W)))
  val data_offset = Reg(Vec(numEntries, UInt(offset.W)))
  val data_lineflag_v_p_addr = Reg(Vec(numEntries, Bool())) // cache line match flag


  val data_vaddr_cat = Wire(Vec(numEntries, UInt(VAddrWidth.W)))
  val data_paddr_cat = Wire(Vec(numEntries, UInt(PAddrWidth.W)))
  (0 until numEntries).foreach({ case i =>
    data_vaddr_cat(i) := Cat(data_vaddr(i), data_offset(i))
    data_paddr_cat(i) := Cat(data_paddr(i), data_offset(i))
  })

  io.debug_data_v := data_vaddr_cat
  io.debug_data_p := data_paddr_cat

  //s0
  val raddr = io.raddr
  val raddr_ext_v = io.raddr_ext_v
  //s1
  val raddr_s1 = RegNext(raddr)
  val raddr_ext_v_s1 = RegNext(raddr_ext_v)

  (0 until CommonNumRead).foreach({ i =>
    //    io.rdata_p(i) := data_paddr(raddr_s1(i))
    io.rdata_p(i) := Cat(data_paddr(raddr_s1(i)), data_offset(raddr_s1(i)))

    //    io.rdata_v(i) := data_vaddr(raddr_s1(i))
    io.rdata_v(i) := Cat(data_vaddr(raddr_s1(i)), data_offset(raddr_s1(i)))

    io.rlineflag_v_p(i) := data_lineflag_v_p_addr(raddr_s1(i))
  })

  //specialized //todo
  io.rdata_ext_v(0) := Cat(data_vaddr(raddr_ext_v_s1(0)), data_offset(raddr_ext_v_s1(0)))

  (0 until CommonNumWrite).foreach({ i =>
    when(io.wen(i)) {
      data_vaddr(io.waddr(i)) := io.wdata_v(i)(VAddrWidth - 1, offset)
      data_paddr(io.waddr(i)) := io.wdata_p(i)(PAddrWidth - 1, offset)

      assert(io.wdata_p(i)(offset - 1, 0) === io.wdata_v(i)(offset - 1, 0))

      data_offset(io.waddr(i)) := io.wdata_v(i)(offset - 1, 0)

      data_lineflag_v_p_addr(io.waddr(i)) := io.wlineflag(i)
    }
  })

  // content addressed match
  for (i <- 0 until NumForward) {
    for (j <- 0 until numEntries) {
      //vaddr
      val linehit_v = io.forwardMdata_v(i)(VAddrWidth - 1, DCacheLineOffset) === data_vaddr_cat(j)(VAddrWidth - 1, DCacheLineOffset)
      val wordhit_v = io.forwardMdata_v(i)(DCacheLineOffset - 1, DCacheWordOffset) === data_vaddr_cat(j)(DCacheLineOffset - 1, DCacheWordOffset)
      io.forwardMmask_v(i)(j) := linehit_v && (wordhit_v || data_lineflag_v_p_addr(j))
      //paddr
      val linehit_p = io.forwardMdata_p(i)(PAddrWidth - 1, DCacheLineOffset) === data_paddr_cat(j)(PAddrWidth - 1, DCacheLineOffset)
      val wordhit_p = io.forwardMdata_p(i)(DCacheLineOffset - 1, DCacheWordOffset) === data_paddr_cat(j)(DCacheLineOffset - 1, DCacheWordOffset)
      io.forwardMmask_p(i)(j) := linehit_p && (wordhit_p || data_lineflag_v_p_addr(j))
    }
  }

//  // content addressed match
//  for (i <- 0 until NumForward) {
//    for (j <- 0 until numEntries) {
//      //vaddr
//      val linehit_v = io.forwardMdata_v(i)(VAddrWidth - 1, DCacheLineOffset) === data_vaddr(j)(VAddrWidth - 1, DCacheLineOffset)
//      val wordhit_v = io.forwardMdata_v(i)(DCacheLineOffset - 1, DCacheWordOffset) === data_vaddr(j)(DCacheLineOffset - 1, DCacheWordOffset)
//      io.forwardMmask_v(i)(j) := linehit_v && (wordhit_v || data_lineflag_v_p_addr(j))
//      //paddr
//      val linehit_p = io.forwardMdata_p(i)(PAddrWidth - 1, DCacheLineOffset) === data_paddr(j)(PAddrWidth - 1, DCacheLineOffset)
//      val wordhit_p = io.forwardMdata_p(i)(DCacheLineOffset - 1, DCacheWordOffset) === data_paddr(j)(DCacheLineOffset - 1, DCacheWordOffset)
//      io.forwardMmask_p(i)(j) := linehit_p && (wordhit_p || data_lineflag_v_p_addr(j))
//    }
//  }


  // DataModuleTemplate should not be used when there're any write conflicts
  for (i <- 0 until CommonNumWrite) {
    for (j <- i + 1 until CommonNumWrite) {
      assert(!(io.wen(i) && io.wen(j) && io.waddr(i) === io.waddr(j)))
    }
  }
}



// Data module define
// These data modules are like SyncDataModuleTemplate, but support cam-like ops
class SQAddrModule(dataWidth: Int, numEntries: Int, numRead: Int, numWrite: Int, numForward: Int)(implicit p: Parameters) extends XSModule with HasDCacheParameters {
  val io = IO(new Bundle {
    // sync read
    val raddr = Input(Vec(numRead, UInt(log2Up(numEntries).W)))
    val rdata = Output(Vec(numRead, UInt(dataWidth.W))) // rdata: store addr
    val rlineflag = Output(Vec(numRead, Bool())) // rdata: line op flag
    // write
    val wen   = Input(Vec(numWrite, Bool()))
    val waddr = Input(Vec(numWrite, UInt(log2Up(numEntries).W)))
    val wdata = Input(Vec(numWrite, UInt(dataWidth.W))) // wdata: store addr
    val wlineflag = Input(Vec(numWrite, Bool())) // wdata: line op flag
    // forward addr cam
    val forwardMdata = Input(Vec(numForward, UInt(dataWidth.W))) // addr
    val forwardMmask = Output(Vec(numForward, UInt(numEntries.W))) // cam result mask
    // debug
    val debug_data = Output(Vec(numEntries, UInt(dataWidth.W)))
  })

  val data = Reg(Vec(numEntries, UInt(dataWidth.W)))
  val lineflag = Reg(Vec(numEntries, Bool())) // cache line match flag
  // if lineflag == true, this address points to a whole cacheline
  io.debug_data := data

  // read ports
  for (i <- 0 until numRead) {
    io.rdata(i) := data(RegNext(io.raddr(i)))
    io.rlineflag(i) := lineflag(RegNext(io.raddr(i)))
  }

  // below is the write ports (with priorities)
  for (i <- 0 until numWrite) {
    when (io.wen(i)) {
      data(io.waddr(i)) := io.wdata(i)
      lineflag(io.waddr(i)) := io.wlineflag(i)
    }
  }

  // content addressed match
  private val forwardMmask = Wire(Vec(numForward, Vec(numEntries, Bool())))
  io.forwardMmask.zip(forwardMmask).foreach({case(a, b) => a := b.asUInt})
  for (i <- 0 until numForward) {
    for (j <- 0 until numEntries) {
      // io.forwardMmask(i)(j) := io.forwardMdata(i)(dataWidth-1, 3) === data(j)(dataWidth-1, 3)
      val linehit = io.forwardMdata(i)(dataWidth-1, DCacheLineOffset) === data(j)(dataWidth-1, DCacheLineOffset)
      val wordhit = io.forwardMdata(i)(DCacheLineOffset-1, DCacheWordOffset) === data(j)(DCacheLineOffset-1, DCacheWordOffset)
      forwardMmask(i)(j) := linehit && (wordhit || lineflag(j))
    }
  }

  // DataModuleTemplate should not be used when there're any write conflicts
  for (i <- 0 until numWrite) {
    for (j <- i+1 until numWrite) {
      assert(!(io.wen(i) && io.wen(j) && io.waddr(i) === io.waddr(j)))
    }
  }
}

class SQData8Entry(implicit p: Parameters) extends XSBundle {
  val valid = Bool() // this byte is valid
  val data = UInt((XLEN/8).W)
}

class SQData8Module(numEntries: Int, numRead: Int, numWrite: Int, numForward: Int)(implicit p: Parameters) extends XSModule 
  with HasDCacheParameters 
  with HasCircularQueuePtrHelper 
{
  val io = IO(new Bundle() {
    // sync read port
    val raddr = Vec(numRead, Input(UInt(log2Up(numEntries).W)))
    val rdata = Vec(numRead, Output(new SQData8Entry))
    // data write port
    val data = new Bundle() {
      val wen   = Vec(numWrite, Input(Bool()))
      val waddr = Vec(numWrite, Input(UInt(log2Up(numEntries).W)))
      val wdata = Vec(numWrite, Input(UInt((XLEN/8).W)))
    }
    // mask (data valid) write port
    val mask = new Bundle() {
      val wen   = Vec(numWrite, Input(Bool()))
      val waddr = Vec(numWrite, Input(UInt(log2Up(numEntries).W)))
      val wdata = Vec(numWrite, Input(Bool()))
    }

    // st-ld forward addr cam result input, used to select forward data
    val needForward = Input(Vec(numForward, Vec(2, UInt(numEntries.W))))
    // forward result valid bit generated in current cycle
    val forwardValidFast = Vec(numForward, Output(Bool()))
    // forward result generated in the next cycle
    val forwardValid = Vec(numForward, Output(Bool())) // forwardValid = RegNext(forwardValidFast)
    val forwardData = Vec(numForward, Output(UInt(8.W)))
  })

  io := DontCare

  val data = Reg(Vec(numEntries, new SQData8Entry))

  require(isPow2(StoreQueueNWriteBanks))
  require(StoreQueueNWriteBanks > 1)
  def get_bank(in: UInt): UInt = in(log2Up(StoreQueueNWriteBanks) -1, 0)
  def get_bank_index(in: UInt): UInt = in >> log2Up(StoreQueueNWriteBanks)
  def get_vec_index(index: Int, bank: Int): Int = {
    (index << log2Up(StoreQueueNWriteBanks)) + bank
  }

  // writeback to sq
  // store queue data write takes 2 cycles
  // (0 until numWrite).map(i => {
  //   when(RegNext(io.data.wen(i))){
  //     data(RegNext(io.data.waddr(i))).data := RegNext(io.data.wdata(i))
  //   }
  // })
  (0 until numWrite).map(i => {
    (0 until StoreQueueNWriteBanks).map(bank => {
      val s0_wen = io.data.wen(i) && get_bank(io.data.waddr(i)) === bank.U
      val s1_wen = RegNext(s0_wen)
      val s1_wdata = RegEnable(io.data.wdata(i), s0_wen)
      val s1_waddr = RegEnable(get_bank_index(io.data.waddr(i)), s0_wen)
      val numRegsPerBank = StoreQueueSize / StoreQueueNWriteBanks
      (0 until numRegsPerBank).map(index => {
        when(s1_wen && s1_waddr === index.U){
          data(get_vec_index(index, bank)).data := s1_wdata
        }
      })
      s0_wen.suggestName("data_s0_wen_" + i +"_bank_" + bank)
      s1_wen.suggestName("data_s1_wen_" + i +"_bank_" + bank)
      s1_wdata.suggestName("data_s1_wdata_" + i +"_bank_" + bank)
      s1_waddr.suggestName("data_s1_waddr_" + i +"_bank_" + bank)
    })
  })

  // (0 until numWrite).map(i => {
  //   when(RegNext(io.mask.wen(i))){
  //     data(RegNext(io.mask.waddr(i))).valid := RegNext(io.mask.wdata(i))
  //   }
  // })
  (0 until numWrite).map(i => {
    (0 until StoreQueueNWriteBanks).map(bank => {
      val s0_wen = io.mask.wen(i) && get_bank(io.mask.waddr(i)) === bank.U
      val s1_wen = RegNext(s0_wen)
      val s1_wdata = RegEnable(io.mask.wdata(i), s0_wen)
      val s1_waddr = RegEnable(get_bank_index(io.mask.waddr(i)), s0_wen)
      val numRegsPerBank = StoreQueueSize / StoreQueueNWriteBanks
      (0 until numRegsPerBank).map(index => {
        when(s1_wen && s1_waddr === index.U){
          data(get_vec_index(index, bank)).valid := s1_wdata
        }
      })
      s0_wen.suggestName("mask_s0_wen_" + i +"_bank_" + bank)
      s1_wen.suggestName("mask_s1_wen_" + i +"_bank_" + bank)
      s1_wdata.suggestName("mask_s1_wdata_" + i +"_bank_" + bank)
      s1_waddr.suggestName("mask_s1_waddr_" + i +"_bank_" + bank)
    })
  })

  // destorequeue read data
  (0 until numRead).map(i => {
      io.rdata(i) := data(RegNext(io.raddr(i)))
  })

  // DataModuleTemplate should not be used when there're any write conflicts
  for (i <- 0 until numWrite) {
    for (j <- i+1 until numWrite) {
      assert(!(io.data.wen(i) && io.data.wen(j) && io.data.waddr(i) === io.data.waddr(j)))
    }
  }
  for (i <- 0 until numWrite) {
    for (j <- i+1 until numWrite) {
      assert(!(io.mask.wen(i) && io.mask.wen(j) && io.mask.waddr(i) === io.mask.waddr(j)))
    }
  }

  // forwarding
  // Compare ringBufferTail (deqPtr) and forward.sqIdx, we have two cases:
  // (1) if they have the same flag, we need to check range(tail, sqIdx)
  // (2) if they have different flags, we need to check range(tail, LoadQueueSize) and range(0, sqIdx)
  // Forward1: Mux(same_flag, range(tail, sqIdx), range(tail, LoadQueueSize))
  // Forward2: Mux(same_flag, 0.U,                   range(0, sqIdx)    )
  // i.e. forward1 is the target entries with the same flag bits and forward2 otherwise

  // entry with larger index should have higher priority since it's data is younger

  (0 until numForward).map(i => {
    // parallel fwd logic
    val matchResultVec = Wire(Vec(numEntries * 2, new FwdEntry))

    def parallelFwd(xs: Seq[Data]): Data = {
      ParallelOperation(xs, (a: Data, b: Data) => {
        val l = a.asTypeOf(new FwdEntry)
        val r = b.asTypeOf(new FwdEntry)
        val res = Wire(new FwdEntry)
        res.validFast := l.validFast || r.validFast
        res.valid := l.valid || r.valid
        // res.valid := RegNext(res.validFast)
        res.data := Mux(r.valid, r.data, l.data)
        res
      })
    }

    for (j <- 0 until numEntries) {
      val needCheck0 = io.needForward(i)(0)(j)
      val needCheck1 = io.needForward(i)(1)(j)
      val needCheck0Reg = RegNext(needCheck0)
      val needCheck1Reg = RegNext(needCheck1)
      (0 until XLEN / 8).foreach(k => {
        matchResultVec(j).validFast := needCheck0 && data(j).valid
        matchResultVec(j).valid := needCheck0Reg && data(j).valid
        matchResultVec(j).data := data(j).data
        matchResultVec(numEntries + j).validFast := needCheck1 && data(j).valid
        matchResultVec(numEntries + j).valid := needCheck1Reg && data(j).valid
        matchResultVec(numEntries + j).data := data(j).data
      })
    }

    val parallelFwdResult = parallelFwd(matchResultVec).asTypeOf(new FwdEntry)

    // validFast is generated the same cycle with query
    io.forwardValidFast(i) := parallelFwdResult.validFast
    // valid is generated 1 cycle after query request
    io.forwardValid(i) := parallelFwdResult.valid
    // data is generated 1 cycle after query request
    io.forwardData(i) := parallelFwdResult.data

  })
}

class SQDataEntry(implicit p: Parameters) extends XSBundle {
  val mask = UInt(8.W)
  val data = UInt(XLEN.W)
}

// SQDataModule is a wrapper of SQData8Modules
class SQDataModule(numEntries: Int, numRead: Int, numWrite: Int, numForward: Int)(implicit p: Parameters) extends XSModule with HasDCacheParameters with HasCircularQueuePtrHelper {
  val io = IO(new Bundle() {
    // sync read port
    val ren   = Vec(numRead,  Input(Bool()))
    val raddr = Vec(numRead,  Input(UInt(log2Up(numEntries).W)))
    val rdata = Vec(numRead,  Output(new SQDataEntry))
    // data write port
    val data = new Bundle() {
      val wen   = Vec(numWrite, Input(Bool()))
      val waddr = Vec(numWrite, Input(UInt(log2Up(numEntries).W)))
      val wdata = Vec(numWrite, Input(UInt(XLEN.W)))
    }
    // mask (data valid) write port
    val mask = new Bundle() {
      val wen   = Vec(numWrite, Input(Bool()))
      val waddr = Vec(numWrite, Input(UInt(log2Up(numEntries).W)))
      val wdata = Vec(numWrite, Input(UInt(8.W)))
    }

    // st-ld forward addr cam result input, used to select forward data
    val needForward = Input(Vec(numForward, Vec(2, UInt(numEntries.W))))
    // forward result valid bit generated in current cycle
    val forwardMaskFast = Vec(numForward, Output(Vec(8, Bool())))
    // forward result generated in the next cycle
    val forwardMask = Vec(numForward, Output(Vec(8, Bool()))) // forwardMask = RegNext(forwardMaskFast)
    val forwardData = Vec(numForward, Output(Vec(8, UInt(8.W))))
  })

  val data8 = Seq.fill(8)(Module(new SQData8Module(numEntries, numRead, numWrite, numForward)))

  // writeback to lq/sq
  for (i <- 0 until numWrite) {
    // write to data8
    for (j <- 0 until 8) {
      data8(j).io.mask.waddr(i) := io.mask.waddr(i)
      data8(j).io.mask.wdata(i) := io.mask.wdata(i)(j)
      data8(j).io.mask.wen(i)   := io.mask.wen(i)
      data8(j).io.data.waddr(i) := io.data.waddr(i)
      data8(j).io.data.wdata(i) := io.data.wdata(i)(8*(j+1)-1, 8*j)
      data8(j).io.data.wen(i)   := io.data.wen(i)
    }
  }

  // destorequeue read data
  for (i <- 0 until numRead) {
    for (j <- 0 until 8) {
      data8(j).io.raddr(i) := io.raddr(i)
    }
    io.rdata(i).mask := VecInit((0 until 8).map(j => data8(j).io.rdata(i).valid)).asUInt
    io.rdata(i).data := VecInit((0 until 8).map(j => data8(j).io.rdata(i).data)).asUInt
  }

  // DataModuleTemplate should not be used when there're any write conflicts
  for (i <- 0 until numWrite) {
    for (j <- i+1 until numWrite) {
      assert(!(io.data.wen(i) && io.data.wen(j) && io.data.waddr(i) === io.data.waddr(j)))
    }
  }
  for (i <- 0 until numWrite) {
    for (j <- i+1 until numWrite) {
      assert(!(io.mask.wen(i) && io.mask.wen(j) && io.mask.waddr(i) === io.mask.waddr(j)))
    }
  }

  (0 until numForward).foreach(i => {
    // parallel fwd logic
    for (j <- 0 until 8) {
      data8(j).io.needForward(i) <> io.needForward(i)
      io.forwardMaskFast(i) := VecInit((0 until 8).map(j => data8(j).io.forwardValidFast(i)))
      io.forwardMask(i) := VecInit((0 until 8).map(j => data8(j).io.forwardValid(i)))
      io.forwardData(i) := VecInit((0 until 8).map(j => data8(j).io.forwardData(i)))
    }
  })
}
