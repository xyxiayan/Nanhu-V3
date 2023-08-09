package xiangshan.mem.prefetch

//add by tjz
import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import utils._
import xiangshan._
import xiangshan.cache.mmu.HasTlbConst
import utility.{SRAMTemplate}

case class SBPParameters(
  rptEntries: Int,
  rptTagBits: Int,
  preVaddrBits: Int,
  rptStrideBits: Int,
  rptStateBits: Int,
  rptTimeMax: Int,
  prefetchCostTimeMax: Int
) {
  def rptIdxBits = log2Up(rptEntries)
  def rptTimeBits = log2Up(rptTimeMax)
}

trait hasSBPParams{
   val l1dStrideParameters= SBPParameters(
     rptEntries = 128,
     rptTagBits = 12,
     preVaddrBits = 14,
     rptStrideBits = 14,
     rptStateBits = 3,
     rptTimeMax = 1024,
     prefetchCostTimeMax = 64
   )
}
class RptResp(implicit p: Parameters) extends XSBundle {
  val respVaddr = UInt(VAddrBits.W)
  //val exuin = new ExuInput
}

class RptEntryResp(implicit p: Parameters) extends XSBundle with hasSBPParams {
  val tag = UInt((l1dStrideParameters.rptTagBits).W)//12bits
  val preVaddr = UInt(l1dStrideParameters.preVaddrBits.W)//12bits
  val stride = UInt(l1dStrideParameters.rptStrideBits.W)//12bits
  val state = UInt((l1dStrideParameters.rptStateBits).W)//3bits
  val cc = UInt((l1dStrideParameters.rptTimeBits).W)//10bits
  val updown = Bool()//1bits
}
class ToStrideReq(implicit p: Parameters) extends XSBundle {
  val pc = UInt(VAddrBits.W)
  val reqVaddr = UInt(VAddrBits.W)
}
class RptBundle(implicit p: Parameters) extends XSBundle with hasSBPParams {
  val reqFromAnotherRpt = DecoupledIO(new ToStrideReq)
  val reqToAnotherRpt = Flipped(DecoupledIO(new ToStrideReq))
  val respToAnotherRpt = Flipped(DecoupledIO(new RptEntryResp))
  val respFromAnotherRpt = DecoupledIO(new RptEntryResp)
  val req = DecoupledIO(new ToStrideReq)
  val resp = Flipped(DecoupledIO(new RptResp))
}

class StrideBasedPrefetch(implicit p: Parameters) extends XSModule with HasTlbConst with hasSBPParams {
  val io = IO(new Bundle{
      //id
      val train = Flipped(new RptBundle)
  })
  def rptIdxBits = l1dStrideParameters.rptIdxBits
  def rptTagBits = l1dStrideParameters.rptTagBits
  def preVaddrBits = l1dStrideParameters.preVaddrBits
  def rptStateBits = l1dStrideParameters.rptStateBits
  def rptEntries = l1dStrideParameters.rptEntries
  def rptStrideBits = l1dStrideParameters.rptStrideBits
  def rptTimeMax = l1dStrideParameters.rptTimeMax
  def rptTimeBits = l1dStrideParameters.rptTimeBits
  def prefetchCostTimeMax = l1dStrideParameters.prefetchCostTimeMax
  def dcachenSets = dcacheParameters.nSets
  def dcacheBlockBytes = dcacheParameters.blockBytes
  def untagBits = log2Up(dcachenSets) + log2Up(dcacheBlockBytes)
  //rpt table is directly mapped, using hashed partial addrs to access
  //each entry holds these: tag(12bits), prevaddrs(10bits), stride(10bits), state(2bits), time(2bits), totally 36bits
  //use PC(9, 2) ^ PC(17, 10) = idx, use idx(8bits) to access Rpt table
  //use PC(21, 10) to be the tag(12 bits)
  //use reqVaddr(11, 2) tp be the preVaddr, use the new and old reqVaddr's difference to be teh stride
  //Also, the reqVaddr(1, 0) was ignored because the Granularity of calculate addr is 32bits(4B)
  //use the state to record the stride's credibility
  //when tag dosen't match, use the time to decide abandone it or not(when pc miss/hit, time -/+1. when time ==0, abandone the tag)
  def usedPc(pc: UInt) = pc(VAddrBits - 1, 2) //pc(38, 2), except pc(1, 0), 37.W
  def hash1(pc: UInt) = usedPc(pc)(rptIdxBits - 1, 0) //pc(9. 2), 8.W
  def hash2(pc: UInt) = usedPc(pc)(2 * rptIdxBits - 1, rptIdxBits)//pc(17, 10), 8.W
  def idx(pc: UInt) = hash1(pc) ^ hash2(pc)//8.W
  def tag(pc: UInt) = usedPc(pc)(rptTagBits + rptIdxBits - 1, rptIdxBits)//pc(21, 10)//12.W
  //above is relevant about pc
  def usedVaddr(vaddr: UInt) = vaddr((preVaddrBits + 1), 2)//12.W
  //def getPageNum(vaddr: UInt) = vaddr(VAddrBits - 1, untagBits)//vaddr(38, 14), 25.W
  def getIdxNum(vaddr: UInt) = vaddr(offLen - 1, log2Up(dcacheBlockBytes))
  
  def rptTableEntry() = new Bundle {
    val tag = UInt(rptTagBits.W)//12bits
    val preVaddr = UInt(preVaddrBits.W)//12bits
    val stride = UInt(rptStrideBits.W)//12bits
    val state = UInt(rptStateBits.W)//3bits
    val cc = UInt(rptTimeBits.W)
    val updown = Bool()
  }

  val clock_in_stride = Counter(rptTimeMax)
  clock_in_stride.inc()

  //check whether crosspage
  //val crosspage = Wire(Bool())
  //check whether the addr is at the same cacheline
  val sameline = Wire(Bool())
  //stride is good
  val stride_is_good_self = Wire(Bool())
  val stride_is_good_other = Wire(Bool())
  //tag is good
  val tag_is_good_self = Wire(Bool())
  val tag_is_good_other = Wire(Bool())

  val self_is_good = Wire(Bool())
  val other_is_good = Wire(Bool())

  val s_idle :: s_init :: s_tran :: s_stand :: s_steady :: Nil = Enum(5)
  //below is entry for write
  val tag_new = RegInit(0.U(rptTagBits.W))
  val preVaddr_new = RegInit(0.U(preVaddrBits.W))
  val stride_new = RegInit(0.U(rptStrideBits.W))
  val state_new = RegInit(s_idle)
  val cc_new = RegInit(0.U(rptTimeBits.W))
  val updown_new = RegInit(true.B)

  //below is for update, which's 1 cycle before writs
  val pc_forupdate = RegNext(io.train.req.bits.pc)
  val vaddr_forupdate = RegNext(io.train.req.bits.reqVaddr)
  val valid_forupdate = RegNext(io.train.req.valid)
  //below is from another apt's req
  val req_fromAnotherRpt_pcUpdate = RegNext(io.train.reqFromAnotherRpt.bits.pc)
  val req_fromAnotherRpt_vaddrUpdate = RegNext(io.train.reqFromAnotherRpt.bits.reqVaddr)
  val req_fromAnotherRpt_validUpdate = RegNext(io.train.reqFromAnotherRpt.valid)
  //below is for write             
  val pc_forwrite = RegNext(pc_forupdate)
  val vaddr_forout = RegNext(vaddr_forupdate)
  val valid_forwrite = RegNext(valid_forupdate)
  //below is req from ld0 & ld1, they read different, write in the same  
  val rAddr0 = io.train.req.bits.pc
  val rAddr1 = io.train.reqFromAnotherRpt.bits.pc
  val oldEntryRespToSelfRpt = Wire(rptTableEntry())
  val oldEntryRespToAnotherRpt = Wire(rptTableEntry())
  val wAddr = pc_forwrite

  val rptTables = (0 until SbpPrefetchSize) map { bank =>
    val rptTable = Module(new SRAMTemplate(rptTableEntry(), set = rptEntries, way = 1, shouldReset = true, singlePort = false))
    // connection
    if(bank == 0) {
      rptTable.io.r.req.valid := io.train.req.fire()
      rptTable.io.r.req.bits.setIdx := idx(rAddr0)
      oldEntryRespToSelfRpt.tag := rptTable.io.r.resp.data(0).tag
      oldEntryRespToSelfRpt.preVaddr := rptTable.io.r.resp.data(0).preVaddr
      oldEntryRespToSelfRpt.stride := rptTable.io.r.resp.data(0).stride
      oldEntryRespToSelfRpt.state := rptTable.io.r.resp.data(0).state
      oldEntryRespToSelfRpt.cc := rptTable.io.r.resp.data(0).cc
      oldEntryRespToSelfRpt.updown := rptTable.io.r.resp.data(0).updown
    }else{
      rptTable.io.r.req.valid := io.train.reqFromAnotherRpt.fire()
      rptTable.io.r.req.bits.setIdx := idx(rAddr1)
      oldEntryRespToAnotherRpt.tag := rptTable.io.r.resp.data(0).tag
      oldEntryRespToAnotherRpt.preVaddr := rptTable.io.r.resp.data(0).preVaddr
      oldEntryRespToAnotherRpt.stride := rptTable.io.r.resp.data(0).stride
      oldEntryRespToAnotherRpt.state := rptTable.io.r.resp.data(0).state
      oldEntryRespToAnotherRpt.cc := rptTable.io.r.resp.data(0).cc
      oldEntryRespToAnotherRpt.updown := rptTable.io.r.resp.data(0).updown
    }
    rptTable.io.w.req.valid := valid_forwrite
    rptTable.io.w.req.bits.setIdx := idx(wAddr)
    rptTable.io.w.req.bits.data(0).tag := tag_new
    rptTable.io.w.req.bits.data(0).preVaddr := preVaddr_new
    rptTable.io.w.req.bits.data(0).stride := stride_new
    rptTable.io.w.req.bits.data(0).state := state_new
    rptTable.io.w.req.bits.data(0).cc := cc_new
    rptTable.io.w.req.bits.data(0).updown := updown_new

    rptTable
  }

  //to solve the r/w in the same address and time
  val rw_same_self = RegInit(false.B)
  rw_same_self := (idx(io.train.req.bits.pc) === idx(pc_forwrite)) && io.train.req.valid && valid_forwrite
  //to solve write&&read at the same entry in the same time
  when(rw_same_self) {
    oldEntryRespToSelfRpt.tag := RegNext(tag_new)
    oldEntryRespToSelfRpt.preVaddr := RegNext(preVaddr_new)
    oldEntryRespToSelfRpt.stride := RegNext(stride_new)
    oldEntryRespToSelfRpt.state := RegNext(state_new)
    oldEntryRespToSelfRpt.cc := RegNext(cc_new)
    oldEntryRespToSelfRpt.updown := RegNext(updown_new)
  }
  val rw_same_other = RegInit(false.B)
  rw_same_other := (idx(io.train.reqFromAnotherRpt.bits.pc) === idx(pc_forwrite)) && io.train.req.valid && valid_forwrite
  when(rw_same_other) {
    oldEntryRespToAnotherRpt.tag := RegNext(tag_new)
    oldEntryRespToAnotherRpt.preVaddr := RegNext(preVaddr_new)
    oldEntryRespToAnotherRpt.stride := RegNext(stride_new)
    oldEntryRespToAnotherRpt.state := RegNext(state_new)
    oldEntryRespToAnotherRpt.cc := RegNext(cc_new)
    oldEntryRespToAnotherRpt.updown := RegNext(updown_new)
  }
  //to solve r/r back to back
  val rr_same_self = RegInit(false.B)
  rr_same_self := idx(io.train.req.bits.pc) === idx(pc_forupdate) && io.train.req.valid && valid_forupdate
  when(rr_same_self) {
    oldEntryRespToSelfRpt.tag := tag_new
    oldEntryRespToSelfRpt.preVaddr := preVaddr_new
    oldEntryRespToSelfRpt.stride := stride_new
    oldEntryRespToSelfRpt.state := state_new
    oldEntryRespToSelfRpt.cc := cc_new
    oldEntryRespToSelfRpt.updown := updown_new
  }
  val rr_same_other = RegInit(false.B)
  rr_same_other := idx(io.train.reqFromAnotherRpt.bits.pc) === idx(pc_forupdate) && io.train.req.valid && valid_forupdate
  when(rr_same_other) {
    oldEntryRespToAnotherRpt.tag := tag_new
    oldEntryRespToAnotherRpt.preVaddr := preVaddr_new
    oldEntryRespToAnotherRpt.stride := stride_new
    oldEntryRespToAnotherRpt.state := state_new
    oldEntryRespToAnotherRpt.cc := cc_new
    oldEntryRespToAnotherRpt.updown := updown_new
  }

  val cc_forupdate = RegInit(0.U(rptTimeBits.W))  
  cc_forupdate := clock_in_stride.value
  cc_new := cc_forupdate

  // now, we get two oldEntries, one comes from selfRpt, one comes from anotherRpt
  val oldEntryRespFromAnotherRpt = io.train.respFromAnotherRpt
//and oldEntryRespToSelfRpt
  // we will compare three vaddrs
  // 1.vaddr_forupdate; 2.oldEntryRespToSelfRpt.respVadr; 3.oldEntryRespFromAnotherRpt.respVaddr.
  val stride_min  = WireInit(0.U(rptStrideBits.W))
  val strideSubSelf = WireInit(0.U(rptStrideBits.W))
  val strideSubAnother = WireInit(0.U(rptStrideBits.W))
  val updown_update = WireInit(true.B)
  //val engage_state = Mux(oldEntryRespFromAnotherRpt.bits.state >= oldEntryRespToSelfRpt.state, oldEntryRespFromAnotherRpt.bits.state, oldEntryRespToSelfRpt.state)
  val engage_state = WireInit(s_idle)
  val engage_cc = WireInit(0.U(rptTimeBits.W))
  val state_better = WireInit(false.B)
  //to get the minest stride, for example, stride 2
  //0 4 10 56  76
  //2 6 8  44  66
  //above is two loadlines but they have the same pc, we need to find out the stride = 2

  tag_is_good_self := oldEntryRespToSelfRpt.tag === tag(pc_forupdate) 
  tag_is_good_other := oldEntryRespFromAnotherRpt.bits.tag === tag(pc_forupdate)

  when((pc_forupdate === req_fromAnotherRpt_pcUpdate) && valid_forupdate && req_fromAnotherRpt_validUpdate) {
    stride_min := Mux(vaddr_forupdate >= req_fromAnotherRpt_vaddrUpdate, 
    usedVaddr(vaddr_forupdate) - usedVaddr(req_fromAnotherRpt_vaddrUpdate),
    usedVaddr(req_fromAnotherRpt_vaddrUpdate) - usedVaddr(vaddr_forupdate)
    )
    updown_update := true.B
    engage_cc := cc_forupdate
  }.otherwise {
    when(usedVaddr(vaddr_forupdate) >= oldEntryRespToSelfRpt.preVaddr){
      strideSubSelf := usedVaddr(vaddr_forupdate) - oldEntryRespToSelfRpt.preVaddr
      updown_update := true.B
    }.otherwise {
      strideSubSelf := oldEntryRespToSelfRpt.preVaddr - usedVaddr(vaddr_forupdate)
      updown_update := false.B
    }
    when(usedVaddr(vaddr_forupdate) >= oldEntryRespFromAnotherRpt.bits.preVaddr){
      strideSubAnother := usedVaddr(vaddr_forupdate) - oldEntryRespFromAnotherRpt.bits.preVaddr
      updown_update := true.B
    }.otherwise {
      strideSubAnother := oldEntryRespFromAnotherRpt.bits.preVaddr - usedVaddr(vaddr_forupdate)
      updown_update := false.B
    }
    stride_min := Mux(((strideSubAnother < strideSubSelf) || !tag_is_good_self) && tag_is_good_other, strideSubAnother, strideSubSelf)
    engage_cc := Mux(oldEntryRespToSelfRpt.cc >= oldEntryRespFromAnotherRpt.bits.cc, oldEntryRespToSelfRpt.cc, oldEntryRespFromAnotherRpt.bits.cc)
  }
  when(oldEntryRespToSelfRpt.state === s_steady || oldEntryRespFromAnotherRpt.bits.state === s_steady){
    stride_is_good_self := (stride_min === oldEntryRespToSelfRpt.stride) && (stride_min =/= 0.U)
    stride_is_good_other := (stride_min === oldEntryRespFromAnotherRpt.bits.stride) && (stride_min =/= 0.U)
    //stride_is_good := (stride_min === oldEntryRespToSelfRpt.stride || stride_min === oldEntryRespFromAnotherRpt.bits.stride) && (stride_min =/= 0.U)
  }.otherwise{
    stride_is_good_self := (stride_min === oldEntryRespToSelfRpt.stride) && (updown_update === oldEntryRespToSelfRpt.updown) && (stride_min =/= 0.U)
    stride_is_good_other := (stride_min === oldEntryRespFromAnotherRpt.bits.stride) && (updown_update === oldEntryRespFromAnotherRpt.bits.updown) && (stride_min =/= 0.U)
  }

  self_is_good  := stride_is_good_self && tag_is_good_self
  other_is_good := stride_is_good_other && tag_is_good_other

  state_better := ((oldEntryRespFromAnotherRpt.bits.state > oldEntryRespToSelfRpt.state) || !tag_is_good_self) && other_is_good
  engage_state := Mux(state_better, oldEntryRespFromAnotherRpt.bits.state, oldEntryRespToSelfRpt.state)
  //statemachine
  when(engage_state === s_idle) {
    when(!(tag_is_good_self || tag_is_good_other)) {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := 0.U
      state_new := s_idle
      updown_new := true.B
    }.otherwise {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := stride_min
      updown_new := updown_update
      state_new := s_init
    }
  }

  when(engage_state === s_init) {
    when(self_is_good || other_is_good) {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := stride_min
      state_new := s_tran
      updown_new := updown_update
    }.elsewhen(!(tag_is_good_self || tag_is_good_other)) {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := 0.U
      state_new := s_idle
      updown_new := true.B
    }.elsewhen(!(stride_is_good_self || stride_is_good_other)) {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := stride_min
      updown_new := updown_update
      state_new := s_init
    }
  }

  when(engage_state === s_tran) {
    when(self_is_good || other_is_good) {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := stride_min
      state_new := s_stand
      updown_new := updown_update
    }.elsewhen(!(tag_is_good_self || tag_is_good_other)) {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := 0.U
      state_new := s_idle
      updown_new := true.B
    }.elsewhen(!(stride_is_good_self || stride_is_good_other)) {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := stride_min
      updown_new := updown_update
      state_new := s_init
    }
  }

  when(engage_state === s_stand) {
    when(self_is_good || other_is_good) {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := stride_min
      state_new := s_steady
      updown_new := updown_update
    }.elsewhen(!(tag_is_good_self || tag_is_good_other)) {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := 0.U
      state_new := s_idle
      updown_new := true.B
    }.elsewhen(!(stride_is_good_self || stride_is_good_other)) {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := stride_min
      updown_new := updown_update
      state_new := s_init
    }
  }

  when(engage_state === s_steady) {
    when(self_is_good || other_is_good) {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := stride_min
      state_new := s_steady
      updown_new := updown_update
    }.elsewhen(!(tag_is_good_self || tag_is_good_other)) {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := 0.U
      state_new := s_idle
      updown_new := true.B
    }.elsewhen(!(stride_is_good_self || stride_is_good_other)) {
      tag_new := tag(pc_forupdate)
      preVaddr_new := usedVaddr(vaddr_forupdate)
      stride_new := stride_min
      updown_new := updown_update
      state_new := s_init
    }
  }

  //contol the in ready
  io.train.req.ready := true.B
  io.train.reqFromAnotherRpt.ready := true.B
  io.train.respFromAnotherRpt.ready := true.B

  io.train.reqToAnotherRpt.valid := io.train.req.valid
  io.train.reqToAnotherRpt.bits.reqVaddr := io.train.req.bits.reqVaddr
  io.train.reqToAnotherRpt.bits.pc := io.train.req.bits.pc

  io.train.respToAnotherRpt.valid := req_fromAnotherRpt_validUpdate//1 cyclie later
  io.train.respToAnotherRpt.bits.tag := oldEntryRespToAnotherRpt.tag
  io.train.respToAnotherRpt.bits.preVaddr := oldEntryRespToAnotherRpt.preVaddr
  io.train.respToAnotherRpt.bits.stride := oldEntryRespToAnotherRpt.stride
  io.train.respToAnotherRpt.bits.state := oldEntryRespToAnotherRpt.state
  io.train.respToAnotherRpt.bits.cc := oldEntryRespToAnotherRpt.cc
  io.train.respToAnotherRpt.bits.updown := oldEntryRespToAnotherRpt.updown
  //control the out data and out valid
  //check whether cross page
  //check whether in the same cacheline
  val finalPredict_vaddr = WireInit(0.U(VAddrBits.W))
  val timeChecker = RegInit(0.U(rptTimeBits.W))
  when(cc_forupdate < engage_cc) {
      timeChecker := cc_forupdate +& rptTimeMax.U - engage_cc
  }.otherwise {
      timeChecker := cc_forupdate - engage_cc
  }

  when(state_new === s_stand || state_new === s_steady) {
    when(timeChecker <= (prefetchCostTimeMax >> 2).U) {
      when(updown_new){
        finalPredict_vaddr := vaddr_forout + ((stride_new << (log2Up(prefetchCostTimeMax >> 4) + 2)))
      }.otherwise{
        finalPredict_vaddr := vaddr_forout - ((stride_new << (log2Up(prefetchCostTimeMax >> 4) + 2)))
      }
    }.otherwise {
      when(updown_new){
        finalPredict_vaddr := vaddr_forout + (stride_new << 3)
      }.otherwise{
        finalPredict_vaddr := vaddr_forout - (stride_new << 3)
      }
    }
  }
    
  io.train.resp.bits.respVaddr := Cat(finalPredict_vaddr(VAddrBits - 1, log2Up(dcacheBlockBytes)), 0.U((log2Up(dcacheBlockBytes)).W))

  //crosspage := getPageNum(finalPredict_vaddr) =/= getPageNum(vaddr_forout)
  sameline := getIdxNum(finalPredict_vaddr) === getIdxNum(vaddr_forout)
  
  when((state_new === s_stand || state_new === s_steady) && valid_forwrite) {
    when(sameline) {
      io.train.resp.valid := false.B
    }.otherwise {
      io.train.resp.valid := true.B 
    }
  }.otherwise {
    io.train.resp.valid := false.B
  }

  when(io.train.req.fire) {
    XSDebug("rptpcReqSelf: %x rptVaddrReq: %x rptvalidReq: %d\n", rAddr0 ,io.train.req.bits.reqVaddr, rptTables(0).io.r.req.valid)
    XSDebug("rptpcReqOther: %x rptVaddrReq: %x rptvalidReq: %d\n", rAddr1 ,io.train.reqFromAnotherRpt.bits.reqVaddr, rptTables(1).io.r.req.valid)    
  }
  when(valid_forupdate) {
    XSDebug("pc_forupdate: %x vaddr_forupdate: %x cc_forupdate: %d valid_forupdate: %d\n", pc_forupdate, vaddr_forupdate, cc_forupdate ,valid_forupdate)
    XSDebug("tag_old: %x preVaddr_old: %x stride_old: %x state_old: %x cc_old: %d updown_old: %d\n", oldEntryRespToSelfRpt.tag, oldEntryRespToSelfRpt.preVaddr, oldEntryRespToSelfRpt.stride, oldEntryRespToSelfRpt.state, oldEntryRespToSelfRpt.cc, oldEntryRespToSelfRpt.updown)
    XSDebug("tag_from_ot: %x preVaddr_from_ot: %x stride_from_ot: %x state_from_ot: %x cc_from_ot: %x updown_from_ot: %x", oldEntryRespFromAnotherRpt.bits.tag, oldEntryRespFromAnotherRpt.bits.preVaddr, oldEntryRespFromAnotherRpt.bits.stride, oldEntryRespFromAnotherRpt.bits.state, oldEntryRespFromAnotherRpt.bits.cc, oldEntryRespFromAnotherRpt.bits.updown)
  }
  when(valid_forwrite) {
    XSDebug("pc_forwrite: %x vaddr_forout: %x valid_forwrite: %d\n", pc_forwrite, vaddr_forout, valid_forwrite)
    XSDebug("valid_forwrite: %d finaltag: %x finalpreVaddr: %x finalstride: %x finalstate: %x finalcc: %d timeChecker: %d updown_new: %d\n", valid_forwrite, tag_new, finalPredict_vaddr, stride_new, state_new, cc_new, timeChecker, updown_new)
    XSDebug("vaddr_forout: %x finalpreVaddr: %x finalstride: %x timeChecker: %d upORdown: %d\n", vaddr_forout, finalPredict_vaddr, stride_new, timeChecker, updown_new)
  }
  XSPerfAccumulate("fire_pefetch_numbers", io.train.resp.fire)
}
