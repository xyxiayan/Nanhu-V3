package xiangshan.mem.prefetch

//add by tjz
import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import utils._
import xiangshan._
import xiangshan.cache.mmu.HasTlbConst
import utility.{CircularQueuePtr}
class OldListPtr(implicit p: Parameters) extends CircularQueuePtr[OldListPtr](
    p => p(XSCoreParamsKey).StrideOldListSize
){}

class OldListResp(implicit p: Parameters) extends XSBundle with HasTlbConst{
  val full = Bool()
  val old_vaddr = UInt(VAddrBits.W)
}

class OldListEntry(implicit p: Parameters) extends XSModule with HasTlbConst{
  val io = IO(new Bundle {
    val id = Input(UInt())
    val request_read = Input(Bool())
    val request_write = Flipped(DecoupledIO(new RptResp))
    val request_out = ValidIO(new OldListResp)
  })

  val member_full = RegInit(false.B)
  val member_vaddr = RegInit(0.U((VAddrBits).W))

  member_full  := Mux(io.request_write.valid, true.B, member_full)
  member_vaddr := Mux(io.request_write.valid, io.request_write.bits.respVaddr, member_vaddr)

  io.request_write.ready := true.B

  io.request_out.valid := io.request_read
  io.request_out.bits.full := member_full
  io.request_out.bits.old_vaddr := member_vaddr
}

class OldList(implicit p: Parameters) extends XSModule with HasTlbConst{
  val io = IO(new Bundle{
    val req  = Vec(SbpPrefetchSize, Flipped(DecoupledIO(new RptResp)))
    val resp = Vec(SbpPrefetchSize, DecoupledIO(new RptResp))
  })
  // write ptr.
  val write_vec = RegInit(VecInit((0 until SbpPrefetchSize).map(_.U.asTypeOf(new OldListPtr))))
  // write action is 1 cycle behind read action.
  val stride_req = io.req
  val write_reqs = Reg(Vec(SbpPrefetchSize, Valid(new RptResp)))
  // get the 2 dimensions's matrix, which represent the compared result of read reqs. 
  val readMatchVec = stride_req.map(a => stride_req.map(b => b.valid && a.valid && b.bits.respVaddr === a.bits.respVaddr))
  // check the equal situation.
  def canMerge(idx: Int): Bool = {
      Cat(readMatchVec(idx).take(idx)).orR
  }
  //addr: 2, 2 => F, T; 2, 2, 2, 3 => F, F, T, T
  //we get the filtered req.
  def filter_read_reqs() = {
    val reqs = io.req.indices.map{ i =>
      val req = Wire(ValidIO(new RptResp))
      val merge = canMerge(i)
      req.bits := io.req(i).bits
      req.valid := !merge && io.req(i).valid
      req
    }
    reqs
  }
  val read_reqs = filter_read_reqs()
  //check whether exist valid read req
  val exist_readVec = read_reqs.map(a => a.valid)
  //check whether exist valid req after merge
  val check_result = Wire(Vec(SbpPrefetchSize, Bool()))
  val check_result_toU = check_result.asUInt()
  //check whether exist valid write req
  val exist_writeVec = write_reqs.map(a => a.valid)

    //update the write idx
  val offset = Wire(Vec(SbpPrefetchSize, UInt(log2Up(SbpPrefetchSize).W)))
  //establish entries
  val entries = (0 until StrideOldListSize) map { i =>
    val entry = Module(new OldListEntry)
    entry.io.id := i.U

    def write_matchVec() = {
      val matchVecs = write_reqs.indices.map{t =>
        val matchVec = Wire(Bool())
        matchVec := write_reqs(t).valid && (write_vec(offset(t)).value === entry.io.id)
        matchVec
      }
      matchVecs
    }

    val vaddrVec = Wire(Vec(SbpPrefetchSize, new RptResp))
    for (i <- 0 until SbpPrefetchSize) {
      vaddrVec(i).respVaddr := Mux(write_matchVec()(i), write_reqs(i).bits.respVaddr, 0.U)
    }

    entry.io.request_read := Cat(exist_readVec).orR
    entry.io.request_write.valid := Cat(write_matchVec()).orR
    //write_reqs(t).valid && (write_vec(offset(t)).value === i.U)
    entry.io.request_write.bits.respVaddr := vaddrVec.map(_.respVaddr).reduce(_|_)
    entry
  }  
  
  // check the old list, whether there are the same cacheline's addr
  val read_write_matchVec = read_reqs.map(a => write_reqs.map(b => a.valid && b.valid && a.bits.respVaddr === b.bits.respVaddr))
  val read_entry_matchVec = read_reqs.map(a => entries.map(b => a.valid && b.io.request_out.bits.full && b.io.request_out.bits.old_vaddr === a.bits.respVaddr))
  
  //update the write reqs
  for (i <- 0 until SbpPrefetchSize) {
    write_reqs(i).bits.respVaddr := read_reqs(i).bits.respVaddr
    //we need to look both in write'Reg and entries.
    check_result(i) := !(Cat(read_write_matchVec(i)).orR || Cat(read_entry_matchVec(i)).orR) && read_reqs(i).valid
    write_reqs(i).valid := check_result(i)
  }

  for(i <- 0 until SbpPrefetchSize) {
    if (i == 0) {
      offset(i) := 0.U
    } else {
      offset(i) := PopCount(check_result_toU(i -1, 0))
    }
  }
 
  when(Cat(check_result).orR) {
    write_vec := VecInit(write_vec.map(_ + PopCount(check_result_toU)))
  }
  // send out the filtered prefetch vaddr
  for(i <- 0 until SbpPrefetchSize) {
    io.req(i).ready := true.B
    io.resp(i).valid := check_result(i) && io.req(i).valid
    io.resp(i).bits.respVaddr := io.req(i).bits.respVaddr
  }

  for (i <- 0 until SbpPrefetchSize) {
    XSDebug(io.req(i).valid, p"reqvaddr=${Hexadecimal(io.req(i).bits.respVaddr)}\n")
    XSDebug(io.resp(i).valid, p"respvaddr=${Hexadecimal(io.resp(i).bits.respVaddr)}\n")
  }
}