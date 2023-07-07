/***************************************************************************************
 * Copyright (c) 2020-2023 Institute of Computing Technology, Chinese Academy of Sciences
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

/*--------------------------------------------------------------------------------------
    Author: GMX
    Date: 2023-06-28
    email: guanmingxing@bosc.ac.cn

---------------------------------------------------------------------------------------*/

package xiangshan.vector.dispatch

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import difftest._
import utils._
import xiangshan._
import xiangshan.mem.mdp._

import xiangshan.vector._

class VectorDispatchFromRenameIO(implicit p: Parameters) extends VectorBaseBundle {
    val uop = Input(Vec(VIRenameWidth, new MicroOp))
    val mask = Input(UInt(VIRenameWidth.W))
}

class VectorDispatchToDQ(implicit p: Parameters) extends VectorBaseBundle {
    //val uop = Output(Vec(VIRenameWidth, new MicroOp))
    val mask = Output(UInt(VIRenameWidth.W))
}

class VectorDispatchNetwork(implicit p: Parameters) extends VectorBaseModule {
    val io = IO(new Bundle {
        val fromRename = new VectorDispatchFromRenameIO
        val commonMask   = Output(UInt(VIRenameWidth.W))
        val permutationMask = Output(UInt(VIRenameWidth.W))
        val memMask = Output(UInt(VIRenameWidth.W))
    })

    val req_mask = io.fromRename.mask.asBools()
    
    class VectorInstrSelectNetwork(typeNum: Int) extends RawModule {
        val io = IO(new Bundle {
            val req = Input(Vec(VIRenameWidth, new MicroOp))
            val toDqMask = Output(Vec(typeNum, Vec(VIRenameWidth, Bool())))
        })
        io.toDqMask(0) := io.req.map(r => FuType.isVecOther(r.ctrl.fuType))
        io.toDqMask(1) := io.req.map(r => FuType.isVecMem(r.ctrl.fuType))
        io.toDqMask(2) := io.req.map(r => FuType.isVecPermutation(r.ctrl.fuType))
    }

    val selNet = new VectorInstrSelectNetwork(VIRenameWidth)
    selNet.io.req := io.fromRename
    
    io.commonMask := selNet.io.toDqMask(0).asUInt & io.fromRename.mask
    io.permutationMask := selNet.io.toDqMask(1).asUInt & io.fromRename.mask
    io.memMask := selNet.io.toDqMask(2).asUInt & io.fromRename.mask
}
