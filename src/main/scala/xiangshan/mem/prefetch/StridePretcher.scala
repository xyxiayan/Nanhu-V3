package xiangshan.mem.prefetch
import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import xiangshan.cache.HasDCacheParameters
import xiangshan.cache.mmu._
import xiangshan.mem.trace.L1MissTrace
import xs.utils._
import utility.{SRAMTemplate,ChiselDB}

case class StridePrefetcherParams
(
  vaddr_hash_width: Int = 5,
  block_addr_raw_width: Int = 10,
  tfTableEntries: Int = 32,
  sTableEntries: Int = 1024,
  prefetchThreshold: Int = 1,
  pfTableEntries: Int = 32,
  pfTableQueueEntries: Int = 5,
  issueCount: Int = 6
) extends PrefetcherParams

trait HasStridePrefetcherModuleHelper extends HasCircularQueuePtrHelper with HasDCacheParameters
{
  this: HasXSParameter =>
  val stridePrefetcherParams = coreParams.l1dprefetcher.get.asInstanceOf[StridePrefetcherParams]

  val VADDR_HASH_WIDTH = stridePrefetcherParams.vaddr_hash_width
  val BLK_ADDR_RAW_WIDTH = stridePrefetcherParams.block_addr_raw_width
 
  val pageOffsetBits = log2Up(dcacheParameters.pageSize)
  val blkBits = log2Up(dcacheParameters.blockBytes)
  val blkOffsetBits = log2Up(dcacheParameters.pageSize / dcacheParameters.blockBytes)
  val tfTableEntries = stridePrefetcherParams.tfTableEntries
  val sTableEntries = stridePrefetcherParams.sTableEntries
  val prefetchThreshold = stridePrefetcherParams.prefetchThreshold
  val pfTableEntries = stridePrefetcherParams.pfTableEntries
  val pfTableQueueEntries = stridePrefetcherParams.pfTableQueueEntries
  val issueCount = stridePrefetcherParams.issueCount

  val vpageAddrBits = VAddrBits - pageOffsetBits
  val tfTagBits = PAddrBits - pageOffsetBits - log2Up(tfTableEntries)
  val sTagBits = VAddrBits - log2Up(sTableEntries)
  val pfTagBits = PAddrBits - pageOffsetBits - log2Up(pfTableEntries)

  def vaddr_hash(x: UInt): UInt = {
    val width = VADDR_HASH_WIDTH
    val low = x(width - 1, 0)
    val mid = x(2 * width - 1, width)
    val high = x(3 * width - 1, 2 * width)
    low ^ mid ^ high
  }

  def block_addr(x: UInt): UInt = {
    val offset = log2Up(dcacheParameters.blockBytes)
    x(x.getWidth - 1, offset)
  }

  def block_hash_tag(x: UInt): UInt = {
    val blk_addr = block_addr(x)
    val low = blk_addr(BLK_ADDR_RAW_WIDTH - 1, 0)
    val high = blk_addr(BLK_ADDR_RAW_WIDTH - 1 + 3 * VADDR_HASH_WIDTH, BLK_ADDR_RAW_WIDTH)
    val high_hash = vaddr_hash(high)
    Cat(high_hash, low)
  }
}

class PrefetchFilterReq(implicit p: Parameters) extends XSBundle with HasStridePrefetcherModuleHelper {
  val paddr_base = UInt((PAddrBits - blkBits).W)
  val alias = UInt(2.W)
  val confidence = UInt(1.W)
  val is_store = Bool()
  val stride = SInt((blkOffsetBits + 1).W)
  val count = UInt(3.W)
}

class TrainFilterTable()(implicit p: Parameters) extends XSModule with HasStridePrefetcherModuleHelper {
  val io = IO(new Bundle() {
    val req = Flipped(ValidIO(UInt(PAddrBits.W)))
    val filtered = Output(Bool())
  })

  def idx(addr:      UInt) = addr(log2Up(tfTableEntries) - 1, 0)
  def tag(addr:      UInt) = addr(PAddrBits - pageOffsetBits - 1, log2Up(tfTableEntries)) 
  def fTableEntry() = new Bundle {
    val valid = Bool()
    val tag = UInt(tfTagBits.W)
    val bitMap = Vec(64, Bool())
  }
  val fTable = RegInit(VecInit(Seq.fill(tfTableEntries)(0.U.asTypeOf(fTableEntry()))))

  val pageAddr = io.req.bits(PAddrBits - 1, pageOffsetBits)
  val blkOffset = io.req.bits(pageOffsetBits - 1, blkOffsetBits)
  val rData = Wire(fTableEntry())
  rData := fTable(idx(pageAddr))

  val hit = rData.valid && rData.tag === tag(pageAddr)
  val hitForMap = hit && rData.bitMap(blkOffset)
  val wData = Wire(fTableEntry())
  val newBitMap = Wire(Vec(64, Bool()))
  when(hit) {
    newBitMap := rData.bitMap.zipWithIndex.map{ case (b, i) => Mux(i.asUInt === blkOffset, true.B, b)}
  } .otherwise {
    newBitMap := rData.bitMap.zipWithIndex.map{ case (b, i) => Mux(i.asUInt === blkOffset, true.B, false.B)}
  }
  wData.valid := true.B
  wData.tag := tag(pageAddr)
  wData.bitMap := newBitMap
  when(io.req.fire) {
    fTable(idx(pageAddr)) := wData
  }

  io.filtered := hitForMap
}


class PrefetchFilterTable()(implicit p: Parameters) extends XSModule with HasStridePrefetcherModuleHelper {
  val io = IO(new Bundle() {
    val req = Flipped(DecoupledIO(new PrefetchFilterReq))
    val resp = DecoupledIO(new L1PrefetchReq)
  })

  def idx(addr:      UInt) = addr(log2Up(pfTableEntries) - 1, 0)
  def tag(addr:      UInt) = addr(PAddrBits - pageOffsetBits - 1, log2Up(pfTableEntries)) 
  def fTableEntry() = new Bundle {
    val valid = Bool()
    val tag = UInt(pfTagBits.W)
    val bitMap = Vec(64, Bool())
  }

  val inProcess = RegInit(false.B)
  val fTable = Module(
    new SRAMTemplate(fTableEntry(), set = pfTableEntries, way = 1, bypassWrite = true, shouldReset = true)
  )

  val q = Module(new ReplaceableQueueV2(chiselTypeOf(io.req.bits), pfTableQueueEntries))
  q.io.enq <> io.req //change logic to replace the tail entry

  val req = RegEnable(q.io.deq.bits, q.io.deq.fire())
  val req_count = RegEnable(q.io.deq.bits.count, q.io.deq.fire())
  val req_base = RegEnable(q.io.deq.bits.paddr_base, q.io.deq.fire())
  val issue_finish = req_count === 0.U
  q.io.deq.ready := !inProcess || issue_finish

  val rData = Wire(fTableEntry())
  val hit = WireDefault(false.B)
  val enread = WireDefault(false.B)
  val prefetchBlock = (req_base.asSInt + req.stride).asUInt
  val pageAddr = prefetchBlock(PAddrBits - blkBits - 1, blkOffsetBits)
  val blkOffset = prefetchBlock(blkOffsetBits - 1, 0)

  fTable.io.r.req.valid := enread
  fTable.io.r.req.bits.setIdx := idx(pageAddr)
  rData := fTable.io.r.resp.data(0)
  hit := rData.valid && rData.tag === tag(RegNext(pageAddr))
  val hitForMap = hit && rData.bitMap(RegNext(blkOffset))

  val newBitMap = Wire(Vec(64, Bool()))
  when(hit) {
    newBitMap := rData.bitMap.zipWithIndex.map{ case (b, i) => Mux(i.asUInt === RegNext(blkOffset), true.B, b)}
  } .otherwise {
    newBitMap := rData.bitMap.zipWithIndex.map{ case (b, i) => Mux(i.asUInt === RegNext(blkOffset), true.B, false.B)}
  }
  fTable.io.w.req.valid := !hitForMap && RegNext(fTable.io.r.req.fire())
  fTable.io.w.req.bits.setIdx := idx(RegNext(pageAddr))
  fTable.io.w.req.bits.data(0).valid := true.B
  fTable.io.w.req.bits.data(0).tag := tag(RegNext(pageAddr))
  fTable.io.w.req.bits.data(0).bitMap := newBitMap

  io.resp.valid := !hitForMap && RegNext(fTable.io.r.req.fire())
  io.resp.bits.paddr := Cat(RegNext(prefetchBlock), 0.U(blkBits.W))
  io.resp.bits.alias := RegNext(req.alias)
  io.resp.bits.confidence := RegNext(req.confidence)
  io.resp.bits.is_store := RegNext(req.is_store)

  when(inProcess) {
    when(!issue_finish) {
      val crossPage = prefetchBlock(PAddrBits - blkBits - 1, blkOffsetBits) =/= 
                            req_base(PAddrBits - blkBits - 1, blkOffsetBits)
      enread := !crossPage
      req_count := Mux(crossPage, 0.U, req_count - 1.U)
      req_base := (req_base.asSInt + req.stride).asUInt
    } .otherwise {
      when(!q.io.deq.fire()) {
        inProcess := false.B
      }
    }
  } .otherwise {
    when(q.io.deq.fire()) {
      inProcess := true.B
    }
  }

  io.req.ready := fTable.io.r.req.ready
}


class StridePrefetcher()(implicit p: Parameters) extends BasePrefecher with HasStridePrefetcherModuleHelper {
  require(exuParameters.LduCnt == 2)

  val tfTable = Module(new TrainFilterTable())
  val pfTable = Module(new PrefetchFilterTable())

  io.tlb_req <> DontCare
  val ld_curr = io.ld_in.map(_.bits)
  val ld_curr_block_tag = ld_curr.map(x => block_hash_tag(x.vaddr))

  // block filter
  val ld_prev = io.ld_in.map(ld => RegEnable(ld.bits, ld.valid))
  val ld_prev_block_tag = ld_curr_block_tag.zip(io.ld_in.map(_.valid)).map({
    case (tag, v) => RegEnable(tag, v)
  })
  val ld_prev_vld = io.ld_in.map(ld => RegNext(ld.valid, false.B))

  val ld_curr_match_prev = ld_curr_block_tag.map(cur_tag =>
    Cat(ld_prev_block_tag.zip(ld_prev_vld).map({
      case (prev_tag, prev_vld) => prev_vld && prev_tag === cur_tag
    })).orR
  )
  val ld0_match_ld1 = io.ld_in.head.valid && io.ld_in.last.valid && ld_curr_block_tag.head === ld_curr_block_tag.last
  val ld_curr_vld = Seq(
    io.ld_in.head.valid && !ld_curr_match_prev.head,
    io.ld_in.last.valid && !ld_curr_match_prev.last && !ld0_match_ld1
  )
  val ld0_older_than_ld1 = Cat(ld_curr_vld).andR && isBefore(ld_curr.head.uop.robIdx, ld_curr.last.uop.robIdx)
  val pending_vld = RegNext(Cat(ld_curr_vld).andR, false.B)
  val pending_sel_ld0 = RegNext(Mux(pending_vld, ld0_older_than_ld1, !ld0_older_than_ld1))
  val pending_ld = Mux(pending_sel_ld0, ld_prev.head, ld_prev.last)
  val pending_ld_block_tag = Mux(pending_sel_ld0, ld_prev_block_tag.head, ld_prev_block_tag.last)
  val oldest_ld = Mux(pending_vld,
    pending_ld,
    Mux(ld0_older_than_ld1 || !ld_curr_vld.last, ld_curr.head, ld_curr.last)
  )
  val train_ld = RegEnable(oldest_ld, pending_vld || Cat(ld_curr_vld).orR)
  val train_vld = RegNext(pending_vld || Cat(ld_curr_vld).orR, false.B)

  // the width of pc is VAddrBits
  def idx(addr:      UInt) = addr(log2Up(sTableEntries) - 1, 0)
  def tag(addr:      UInt) = addr(VAddrBits - 1, log2Up(sTableEntries))
  def sTableEntry() = new Bundle {
    val valid = Bool()
    val tag = UInt(sTagBits.W)
    val lastBlock = UInt(blkOffsetBits.W)
    val stride = SInt((blkOffsetBits + 1).W)
    val confidence = UInt(2.W)
  }
  val sTable = Module(
    new SRAMTemplate(sTableEntry(), set = sTableEntries, way = 1, bypassWrite = true, shouldReset = true)
  )
  val train_ld_pc_s0 = train_ld.uop.cf.pc
  val train_ld_va_s0 = train_ld.vaddr
  val train_block_s0 = train_ld_va_s0(pageOffsetBits - 1, blkBits)

  tfTable.io.req.valid := train_vld
  tfTable.io.req.bits := train_ld.paddr
  val filtered = tfTable.io.filtered

  sTable.io.r.req.valid := train_vld && !filtered
  sTable.io.r.req.bits.setIdx := idx(train_ld_pc_s0)

  val rData = Wire(sTableEntry())
  val wData = Wire(sTableEntry())
  val train_ld_pc_s1 = RegNext(train_ld_pc_s0)
  val train_ld_va_s1 = RegNext(train_ld_va_s0)
  val train_ld_pa_s1 = RegNext(train_ld.paddr)
  val train_block_s1 = RegNext(train_block_s0)
  val hit = rData.valid && rData.tag === tag(train_ld_pc_s1)
  val delta : SInt = train_block_s1.asSInt - rData.lastBlock.asSInt
  val mat = delta === rData.stride 
  val enprefetch = WireDefault(false.B)
  rData := sTable.io.r.resp.data(0)
  wData := rData
  wData.lastBlock := train_block_s1
  when(hit) {
    when(mat) {
      wData.confidence := Mux(rData.confidence === ((1.U << rData.confidence.getWidth) - 1.U), 
                            rData.confidence, rData.confidence + 1.U)
      enprefetch := wData.confidence > prefetchThreshold.asUInt
    } .otherwise {
      when(rData.confidence - 1.U < prefetchThreshold.asUInt) {
        wData.stride := delta
        wData.confidence := 1.U
      } .elsewhen(rData.confidence - 1.U === prefetchThreshold.asUInt) {
        wData.confidence := rData.confidence - 1.U
      } .otherwise {
        wData.confidence := rData.confidence - 1.U
        enprefetch := true.B
      }
    }
  } .otherwise {
    wData.valid := true.B
    wData.tag := tag(train_ld_pc_s1)
    wData.stride := delta
    wData.confidence := 1.U
  }
  sTable.io.w.req.valid := RegNext(sTable.io.r.req.fire()) && delta =/= 0.S
  sTable.io.w.req.bits.setIdx := idx(train_ld_pc_s1)
  sTable.io.w.req.bits.data(0) := wData

  pfTable.io.req.valid := delta =/= 0.S && enprefetch
  pfTable.io.req.bits.paddr_base := train_ld_pa_s1(PAddrBits - 1, blkBits)
  pfTable.io.req.bits.alias := train_ld_va_s1(pageOffsetBits + 1 ,pageOffsetBits)
  pfTable.io.req.bits.confidence := 0.U // to optimize
  pfTable.io.req.bits.is_store := false.B // to clear
  pfTable.io.req.bits.stride := wData.stride
  pfTable.io.req.bits.count := issueCount.asUInt

  pfTable.io.resp.ready := true.B

  // to connect remaining interface
  io.l1_pf_req.get.valid := pfTable.io.resp.valid && io.enable
  io.l1_pf_req.get.bits.paddr := pfTable.io.resp.bits.paddr
  io.l1_pf_req.get.bits.alias := pfTable.io.resp.bits.alias
  io.l1_pf_req.get.bits.confidence := pfTable.io.resp.bits.confidence
  io.l1_pf_req.get.bits.is_store := pfTable.io.resp.bits.is_store

  io.pf_addr.valid := false.B
  io.pf_addr.bits := pfTable.io.resp.bits.paddr

  val pf_trace = Wire(new L1MissTrace)
  pf_trace.vaddr := 0.U
  pf_trace.pc := 0.U
  pf_trace.source := 0.U
  pf_trace.paddr := pfTable.io.resp.bits.paddr
  val pf_table = ChiselDB.createTable("L1PrefetchTrace", new L1MissTrace)
  pf_table.log(pf_trace, io.l1_pf_req.get.valid, "StridePrefetcher", clock, reset)

  val train_trace = Wire(new L1MissTrace)
  train_trace.vaddr := train_ld.vaddr
  train_trace.pc := train_ld.uop.cf.pc
  train_trace.source := 0.U
  train_trace.paddr := train_ld.paddr
  val train_table = ChiselDB.createTable("L1TrainTrace", new L1MissTrace)
  train_table.log(train_trace, train_vld, "StridePrefetcher", clock, reset)

  val actual_train_trace = Wire(new L1MissTrace)
  actual_train_trace.vaddr := train_ld.vaddr
  actual_train_trace.pc := train_ld.uop.cf.pc
  actual_train_trace.source := 0.U
  actual_train_trace.paddr := train_ld.paddr
  val actual_train_table = ChiselDB.createTable("L1ActualTrainTrace", new L1MissTrace)
  actual_train_table.log(actual_train_trace, sTable.io.r.req.fire, "StridePrefetcher", clock, reset)
  
  XSPerfAccumulate("l1pf_recv_train", train_vld)
}