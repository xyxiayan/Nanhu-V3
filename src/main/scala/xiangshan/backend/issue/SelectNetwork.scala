/***************************************************************************************
  * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
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
/***************************************************************************************
  * Author: Liang Sen
  * E-mail: liangsen20z@ict.ac.cn
  * Date: 2023-03-31
  ****************************************************************************************/
package xiangshan.backend.issue
import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan.backend.execute.exu.ExuConfig
import xiangshan.backend.rob.RobPtr
import xiangshan.{FuType, Redirect, XSBundle, XSModule}
import xs.utils.{LogicShiftRight, ParallelOperation}

class SelectInfo(implicit p: Parameters) extends XSBundle{
  val fuType = FuType()
  val lpv = Vec(loadUnitNum, UInt(LpvLength.W))
  val pdest = UInt(PhyRegIdxWidth.W)
  val rfWen = Bool()
  val fpWen = Bool()
  val robPtr = new RobPtr
}

class SelectResp(val bankIdxWidth:Int, entryIdxWidth:Int)(implicit p: Parameters) extends XSBundle {
  val info = new SelectInfo
  val entryIdxOH = UInt(entryIdxWidth.W)
  val bankIdxOH = UInt(bankIdxWidth.W)
}

class SelectPolicy(width:Int, oldest:Boolean, haveEqual:Boolean)(implicit p: Parameters) extends Module{
  val io = IO(new Bundle{
    val in = Input(Vec(width, Valid(new RobPtr)))
    val out = Output(Valid(UInt(width.W)))
  })
  override val desiredName:String = s"SelectPolicy_w${width}_" + (if(oldest)"o" else "p")
  if(oldest) {
    val onlyOne = PopCount(io.in.map(_.valid)) === 1.U
    val oldestOHMatrix = io.in.zipWithIndex.map({ case (self, idx) =>
      io.in.zipWithIndex.filterNot(_._2 == idx).map(i => (i._1.valid && self.valid && (self.bits <= i._1.bits)) ^ i._1.valid)
    })
    val oldestOHSeq = oldestOHMatrix.map(_.reduce(_|_)).map(!_)
    val oldestOH = if(haveEqual) PriorityEncoderOH(Cat(oldestOHSeq.reverse)) else Cat(oldestOHSeq.reverse)
    val defaultValue = Cat(io.in.map(_.valid).reverse)
    io.out.valid := io.in.map(_.valid).reduce(_ | _)
    io.out.bits := Mux(onlyOne, defaultValue, oldestOH)
  } else {
    io.out.valid := io.in.map(_.valid).reduce(_ | _)
    io.out.bits := PriorityEncoderOH(Cat(io.in.map(_.valid).reverse))
  }
  when(io.out.valid) {
    assert(PopCount(io.out.bits) === 1.U)
  }
}
/** {{{
  * Module Name: SelectedNetwork
  *
  * Function Description:
  *   Select ready and supported instructions from several
  *   reservation station banks.
  *
  * Parameters:
  *   bankNum:
  *     The number of banks.
  *   entryNum:
  *     The number of entries in a bank.
  *   issueNum:
  *     The number of issue port for a certain type of instructions.
  *   fuTypeList:
  *     The list of function unit types, to which the selected
  *     instructions should be sent.
  *
  * IO:
  *   selectInfo: [Input][Vec]
  *     The necessary information for selection, which comes from
  *     reservation station banks.
  *   issueInfo: [Output][Vec][Valid]
  *     info:
  *       The information of selected instruction.
  *     bankIdxOH:
  *       The one hot format bank index of selected instruction.
  *     entryIdxOH:
  *       The one hot format entry index of selected instruction.
  *   tokenRelease: [Optional][UInt]
  *     Bit vector to release token.
  *     Some FU may need uncertain clocks to finished its job.
  *     Issuing an instruction of these kind of FUs consumes 1 token.
  *     Stop issuing instructions when running out of tokens.
  *     After these FUs finished a job, release a token to token allocator.
  *     We replace valid-ready handshake with token-release handshake to
  *     cut off ready signal propagation.
  * }}}
*/

class SelectNetwork(bankNum:Int, entryNum:Int, issueNum:Int, val cfg:ExuConfig, oldest:Boolean, haveEqual:Boolean, name:Option[String] = None)(implicit p: Parameters) extends XSModule {
  require(issueNum <= bankNum && 0 < issueNum && bankNum % issueNum == 0, "Illegal number of issue ports are supported now!")
  private val fuTypeList = cfg.fuConfigs.map(_.fuType)
  val io = IO(new Bundle{
    val redirect = Input(Valid(new Redirect))
    val selectInfo = Input(Vec(bankNum,Vec(entryNum, Valid(new SelectInfo))))
    val issueInfo = Vec(issueNum, Decoupled(new SelectResp(bankNum, entryNum)))
    val earlyWakeUpCancel = Input(Vec(loadUnitNum, Bool()))
    val tokenRelease = if(cfg.needToken)Some(Input(Vec(issueNum,Valid(UInt(PhyRegIdxWidth.W))))) else None
  })
  override val desiredName:String = name.getOrElse("SelectNetwork")

  private val selectResultsPerBank = io.selectInfo.zipWithIndex.map({case(si, bidx) =>
    val primaryResult = Wire(Valid(new SelectResp(bankNum, entryNum)))
    val primarySelector = Module(new SelectPolicy(entryNum, oldest, haveEqual))
    primarySelector.io.in.zip(si).foreach({case(a, b) =>
      a.valid := b.valid && cfg.fuConfigs.map(_.fuType === b.bits.fuType).reduce(_|_)
      a.bits := b.bits.robPtr
    })
    primaryResult.valid := primarySelector.io.out.valid
    primaryResult.bits.info := Mux1H(primarySelector.io.out.bits, si.map(_.bits))
    primaryResult.bits.entryIdxOH := primarySelector.io.out.bits
    primaryResult.bits.bankIdxOH := (1 << bidx).U(bankNum.W)
    primaryResult
  })

  private val finalSelectResult = Wire(Vec(issueNum, Valid(new SelectResp(bankNum, entryNum))))
  if(bankNum == issueNum){
    finalSelectResult.zip(selectResultsPerBank).foreach({case(a, b) => a := b})
  } else {
    val bankNumPerIss = bankNum / issueNum
    finalSelectResult.zipWithIndex.foreach({case(res, i) =>
      val selBanks = selectResultsPerBank.slice(i * bankNumPerIss, i * bankNumPerIss + bankNumPerIss)
      val secondarySelector = Module(new SelectPolicy(bankNumPerIss, oldest, haveEqual))
      secondarySelector.io.in.zip(selBanks).foreach({ case (a, b) =>
        a.valid := b.valid
        a.bits := b.bits.info.robPtr
      })
      res.valid := secondarySelector.io.out.valid
      res.bits := Mux1H(secondarySelector.io.out.bits, selBanks.map(_.bits))
    })
  }

  if(cfg.needToken){
    val tokenAllocators = Seq.fill(issueNum)(Module(new TokenAllocator(PhyRegIdxWidth, cfg.fuConfigs.length)))
    for ((((outPort, driver), ta), tr) <- io.issueInfo.zip(finalSelectResult).zip(tokenAllocators).zip(io.tokenRelease.get)) {
      ta.io.redirect := io.redirect
      ta.io.alloc.valid := outPort.fire
      ta.io.alloc.bits.pdest := driver.bits.info.pdest
      ta.io.alloc.bits.robPtr := driver.bits.info.robPtr
      ta.io.alloc.bits.lpv := outPort.bits.info.lpv
      ta.io.earlyWakeUpCancel := io.earlyWakeUpCancel
      ta.io.release := tr
      val shouldBeFlushed = driver.bits.info.robPtr.needFlush(io.redirect)
      val shouldBeCancelled = driver.bits.info.lpv.zip(io.earlyWakeUpCancel).map({case(l, c)=>l(0) & c}).reduce(_|_)
      outPort.valid := driver.valid && ta.io.allow && !shouldBeCancelled && !shouldBeFlushed
      outPort.bits.bankIdxOH := driver.bits.bankIdxOH
      outPort.bits.entryIdxOH := driver.bits.entryIdxOH
      outPort.bits.info := driver.bits.info
      outPort.bits.info.lpv.zip(driver.bits.info.lpv).foreach({case(o, i) => o := LogicShiftRight(i, 1)})
    }
  } else {
    for ((outPort, driver) <- io.issueInfo.zip(finalSelectResult)) {
      val shouldBeFlushed = driver.bits.info.robPtr.needFlush(io.redirect)
      val shouldBeCancelled = driver.bits.info.lpv.zip(io.earlyWakeUpCancel).map({case(l, c)=>l(0) & c}).reduce(_|_)
      outPort.valid := driver.valid && !shouldBeCancelled && !shouldBeFlushed
      outPort.bits.bankIdxOH := driver.bits.bankIdxOH
      outPort.bits.entryIdxOH := driver.bits.entryIdxOH
      outPort.bits.info := driver.bits.info
      outPort.bits.info.lpv.zip(driver.bits.info.lpv).foreach({case(o, i) => o := LogicShiftRight(i, 1)})
    }
  }
}