package dla.eyerissTop

import chisel3._
import chisel3.util._
import dla.pe.{CSCStreamIO, PESizeConfig}

class CSCSwitcherIO(private val adrWidth: Int) extends Bundle with PESizeConfig {
  val inData: DecoupledIO[UInt] = Flipped(Decoupled(UInt(cscDataWidth.W)))
  val outData = new CSCStreamIO(adrWidth = adrWidth, dataWidth = cscDataWidth + cscCountWidth)
  /** use matrix height and width to increase and wrap csc address and count reg */
  val matrixHeight: UInt = Input(UInt(5.W)) // TODO: check the width
  val matrixWidth: UInt = Input(UInt(5.W))
  val vectorNum: UInt = Input(UInt(5.W))
}

class CSCSwitcher(private val adrWidth: Int) extends Module with PESizeConfig {
  val io: CSCSwitcherIO = IO(new CSCSwitcherIO(adrWidth = adrWidth))
  private val dataWidth = cscDataWidth + cscCountWidth
  private val zeroCode = if (adrWidth == inActAdrWidth) inActZeroColumnCode else weightZeroColumnCode
  // TODO: generate SIMD csc for weight
  private val inData = Queue(io.inData, fifoSize, flow = true, pipe = true)
  private val outAdr = Wire(Decoupled(UInt(adrWidth.W)))
  private val outData = Wire(Decoupled(UInt(dataWidth.W)))
  private val cscCountReg = RegInit(0.U(cscCountWidth.W))
  private val cscCountPlusOne = cscCountReg + 1.U
  private val cscAdrReg = RegInit(0.U(adrWidth.W))
  private val cscAdrPlusOne = cscAdrReg + 1.U
  private val zeroColReg = RegInit(true.B) // true when current column contains zero only
  private val vectorNumCounter = RegInit(0.U) // counter for padNumbers
  private val vectorNumPlusOne = vectorNumCounter + 1.U
  private val meetNoneZeroWire = Wire(Bool())
  private val oneColFinWire = Wire(Bool())
  private val oneRowFinWire = Wire(Bool())
  private val oneVectorFinRegNext = RegNext(oneColFinWire && oneRowFinWire) // true when process one pad data
  private val oneStreamFinRegNext = RegNext(oneVectorFinRegNext && (vectorNumPlusOne === io.vectorNum))
  /** meetNoneZeroWire will be true when current bits is not zero*/
  meetNoneZeroWire := inData.bits =/= 0.U
  private val currentZeroColumn = oneColFinWire && zeroColReg
  private val outDataShouldValid = meetNoneZeroWire || currentZeroColumn
  private val outAdrShouldValid = oneColFinWire
  /** when cscCountReg equals to the height of matrix, then current column finishes */
  oneColFinWire := io.matrixHeight === cscCountPlusOne
  oneRowFinWire := io.matrixWidth === cscAdrPlusOne
  private val endFlag = oneStreamFinRegNext || oneVectorFinRegNext
  /** when its the last element of one Pad or the whole stream, then ready will be false to stop deq from in queue
    * when any of the out queues is full (out queue.ready is false) then stop deq from in queue
    * but when out queue is full but current data is zero, then we can deq it from in queue*/
  inData.ready := !endFlag && ((outData.ready && outAdr.ready) || !meetNoneZeroWire)
  /** and both csc data and address will be zero when endFlag is true */
  outAdr.bits := Mux(endFlag, 0.U, cscAdrReg)
  outData.bits := Mux(endFlag, 0.U, Mux(currentZeroColumn, zeroCode.U, Cat(inData.bits, cscCountReg)))
  /** when [[oneVectorFinRegNext]] equals to true, then pad number should add one */
  vectorNumCounter := Mux(oneVectorFinRegNext, vectorNumPlusOne, vectorNumCounter)
  outData.valid := Mux(endFlag, true.B, outDataShouldValid && inData.valid)
  // TODO: remove `cscAdrReg =/= 0.U` for zero column
  outAdr.valid := Mux(endFlag, true.B, outAdrShouldValid && inData.valid && cscAdrReg =/= 0.U)
  /** when meet none a zero element, zeroColReg will be assigned to false, otherwise keep its value
    * After every column, it will be reset*/
  zeroColReg := Mux(oneColFinWire, true.B, Mux(meetNoneZeroWire, false.B, zeroColReg))
  when (inData.fire()) {
    when (oneColFinWire) {
      cscCountReg := 0.U
      when (oneRowFinWire) {
        cscAdrReg := 0.U
      } .otherwise {
        cscAdrReg := cscAdrPlusOne
      }
    } .otherwise {
      cscCountReg := cscCountPlusOne
    }
  }
  io.outData.adrIOs.data <> Queue(outAdr, fifoSize, pipe = true, flow = true)
  io.outData.dataIOs.data <> Queue(outData, fifoSize, pipe = true, flow = true)
}
