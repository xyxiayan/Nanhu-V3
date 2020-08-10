package xiangshan.frontend

import chisel3._
import chisel3.util._
import xiangshan._
import xiangshan.backend.ALUOpType
import utils._

class RAS extends BasePredictor
{
    class RASResp extends Resp
    {
        val target =UInt(VAddrBits.W)
    }

    class RASBranchInfo extends Meta
    {
        val rasSp = UInt(log2Up(RasSize).W)
        val rasTopCtr = UInt(8.W)
    }

    class RASIO extends DefaultBasePredictorIO 
    {
        val is_ret = Input(Bool())
        val callIdx = Flipped(ValidIO(UInt(log2Ceil(PredictWidth).W)))
        val isRVC = Input(Bool())
        val recover =  Flipped(ValidIO(new BranchUpdateInfo))
        val out = ValidIO(new RASResp)
        val branchInfo = Output(new RASBranchInfo)
    }

    def rasEntry() = new Bundle {
        val retAddr = UInt(VAddrBits.W)
        val ctr = UInt(8.W) // layer of nested call functions
    }
    override val io = IO(new RASIO)

    val ras = Reg(Vec(RasSize, rasEntry()))  //RegInit(0.U)asTypeOf(Vec(RasSize,rasEntry)) cause comb loop
    val sp = RegInit(0.U(log2Up(RasSize).W))

    val is_empty = sp === 0.U
    val is_full = sp === (RasSize - 1).U

    val ras_top_entry = ras(sp-1.U)
    val ras_top_addr = ras_top_entry.retAddr
    val ras_top_ctr = ras_top_entry.ctr
    // save ras checkpoint info
    io.branchInfo.rasSp := sp
    io.branchInfo.rasTopCtr := ras(sp).ctr

    io.out.valid := !is_empty && io.is_ret
    XSDebug("  index       addr           ctr \n")
    for(i <- 0 until RasSize){
        XSDebug("  (%d)   0x%x      %d",i.U,ras(i).retAddr,ras(i).ctr)
        when(i.U === sp){XSDebug(false,true.B,"   <----sp")}
        XSDebug(false,true.B,"\n")
    }
    // update RAS
    // speculative update RAS
    val push = !is_full && io.callIdx.valid && !io.is_ret && io.pc.valid
    val pop = !is_empty && io.is_ret && !io.callIdx.valid && io.pc.valid
    val recover_valid = io.recover.valid && io.recover.bits.isMisPred
    io.out.bits.target := 0.U
    val new_addr = io.pc.bits + (io.callIdx.bits << 1.U) + Mux(io.isRVC,2.U,4.U)
    val rasWrite = WireInit(0.U.asTypeOf(rasEntry()))
    val allocNewEntry = new_addr =/= ras_top_addr
    when (push && !recover_valid) {
        //push
        //XSDebug("d")
        rasWrite.ctr := 1.U
        rasWrite.retAddr := new_addr
        when(allocNewEntry){
            sp := sp + 1.U 
            ras(sp) := rasWrite
        }.otherwise{ 
            ras_top_ctr := ras_top_ctr + 1.U
        }
        XSDebug("push  inAddr: 0x%x  inCtr: %d |  allocNewEntry:%d |   sp:%d \n",rasWrite.retAddr,rasWrite.ctr,allocNewEntry,sp.asUInt)
    }
    
    when (pop && !recover_valid) {
        //pop
        io.out.bits.target := ras_top_addr
        when (ras_top_ctr === 1.U) {
            sp := Mux(sp === 0.U, 0.U, sp - 1.U)
        }.otherwise {
           ras_top_ctr := ras_top_ctr - 1.U
        }
        XSDebug("pop outValid:%d  outAddr: 0x%x \n",io.out.valid,io.out.bits.target)
    }
    // TODO: back-up stack for ras
    // use checkpoint to recover RAS
    val recoverSp = io.recover.bits.brInfo.rasSp
    val recoverCtr = io.recover.bits.brInfo.rasTopCtr
    val recover_top = ras(recoverSp - 1.U)
    when (recover_valid) {
        sp := recoverSp
        ras(recoverSp).ctr := recoverCtr
        XSDebug("RAS update: SP:%d , Ctr:%d \n",recoverSp,recoverCtr)
    }

    val recover_and_push = recover_valid && push
    val recover_and_pop = recover_valid && pop
    val recover_alloc_new = new_addr =/= recover_top.retAddr
    when(recover_and_push)
    {
        when(recover_alloc_new){
            sp := recoverSp + 1.U
            ras(recoverSp).retAddr := new_addr
            ras(recoverSp).ctr := 1.U
        } .otherwise{
            sp := recoverSp
            recover_top.ctr := recoverCtr + 1.U
        }
    } .elsewhen(recover_and_pop)
    {
        io.out.bits.target := recover_top.retAddr
        when ( recover_top.ctr === 1.U) {
            sp := recoverSp - 1.U
        }.otherwise {
            sp := recoverSp
           recover_top.ctr := recoverCtr - 1.U
        }
    }

}