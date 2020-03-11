// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import Chisel._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._

// TODO This class should be moved to package subsystem to resolve
//      the dependency awkwardness of the following imports
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.prci._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.util._

/** Specifies widths of various attachement points in the SoC */
trait HasTLBusParams {
  def beatBytes: Int
  def blockBytes: Int

  def beatBits: Int = beatBytes * 8
  def blockBits: Int = blockBytes * 8
  def blockBeats: Int = blockBytes / beatBytes
  def blockOffset: Int = log2Up(blockBytes)

  def dtsFrequency: Option[BigInt]
  def fixedClockOpt = dtsFrequency.map(f => ClockParameters(freqMHz = f.toDouble / 1000000.0))

  require (isPow2(beatBytes))
  require (isPow2(blockBytes))
}

abstract class TLBusWrapper(params: HasTLBusParams, val busName: String)(implicit p: Parameters)
    extends ClockDomain
    with HasTLBusParams
    with CanHaveBuiltInDevices
    with CanAttachTLSlaves
    with CanAttachTLMasters
{
  private val clockGroupAggregator = LazyModule(new ClockGroupAggregator(busName)).suggestName(busName + "_clock_groups")
  private val clockGroup = LazyModule(new ClockGroup(busName))
  val clockGroupNode = clockGroupAggregator.node // other bus clock groups attach here
  val clockNode = clockGroup.node
  val fixedClockNode = FixedClockBroadcast(fixedClockOpt) // device clocks attach here
  private val clockSinkNode = ClockSinkNode(List(ClockSinkParameters(take = fixedClockOpt)))

  clockGroup.node := clockGroupAggregator.node
  fixedClockNode := clockGroup.node // first member of group is always domain's own clock
  clockSinkNode := fixedClockNode

  def clockBundle = clockSinkNode.in.head._1
  def beatBytes = params.beatBytes
  def blockBytes = params.blockBytes
  def dtsFrequency = params.dtsFrequency
  val dtsClk = fixedClockNode.fixedClockResources(s"${busName}_clock").flatten.headOption

  /* If you violate this requirement, you will have a rough time.
   * The codebase is riddled with the assumption that this is true.
   */
  require(blockBytes >= beatBytes)

  def inwardNode: TLInwardNode
  def outwardNode: TLOutwardNode
  def busView: TLEdge
  def unifyManagers: List[TLManagerParameters] = ManagerUnification(busView.manager.managers)
  def crossOutHelper = this.crossOut(outwardNode)(ValName("bus_xing"))
  def crossInHelper = this.crossIn(inwardNode)(ValName("bus_xing"))

  def to[T](name: String)(body: => T): T = {
    this { LazyScope(s"coupler_to_${name}") { body } }
  }

  def from[T](name: String)(body: => T): T = {
    this { LazyScope(s"coupler_from_${name}") { body } }
  }

  def coupleTo[T](name: String)(gen: TLOutwardNode => T): T =
    to(name) { gen(outwardNode) }

  def coupleFrom[T](name: String)(gen: TLInwardNode => T): T =
    from(name) { gen(inwardNode) }

  def crossToBus(bus: TLBusWrapper, xType: ClockCrossingType)(implicit asyncClockGroupNode: ClockGroupEphemeralNode): NoHandle = {
    bus.clockGroupNode := asyncMux(xType, asyncClockGroupNode, this.clockGroupNode)
    coupleTo(s"bus_named_${bus.busName}") {
      bus.crossInHelper(xType) :*= TLWidthWidget(beatBytes) :*= _
    }
  }

  def crossFromBus(bus: TLBusWrapper, xType: ClockCrossingType)(implicit asyncClockGroupNode: ClockGroupEphemeralNode): NoHandle = {
    bus.clockGroupNode := asyncMux(xType, asyncClockGroupNode, this.clockGroupNode)
    coupleFrom(s"bus_named_${bus.busName}") {
      _ :=* TLWidthWidget(bus.beatBytes) :=* bus.crossOutHelper(xType)
    }
  }
}

trait TLBusWrapperInstantiationLike {
  def instantiate(context: HasLocations, loc: Location[TLBusWrapper])(implicit p: Parameters): TLBusWrapper
}

trait TLBusWrapperConnectionLike {
  val xType: ClockCrossingType
  def inject(implicit p: Parameters): TLNode = TLNameNode("temp")
  def connect(context: HasLocations, from: Location[TLBusWrapper], to: Location[TLBusWrapper])(implicit p: Parameters): Unit
}

case class TLBusWrapperCrossToConnection
    (xType: ClockCrossingType)
    (nodeView: (TLBusWrapper, Parameters) => TLInwardNode = { case(w, p) => w.crossInHelper(xType)(p) },
     inject: Parameters => TLNode = { _ => TLNameNode("temp") })
  extends TLBusWrapperConnectionLike
{
  def connect(context: HasLocations, from: Location[TLBusWrapper], to: Location[TLBusWrapper])(implicit p: Parameters): Unit = {
    val masterTLBus = context.locateTLBusWrapper(from)
    val slaveTLBus  = context.locateTLBusWrapper(to)
    slaveTLBus.clockGroupNode := asyncMux(xType, context.asyncClockGroupsNode, masterTLBus.clockGroupNode)
    masterTLBus.coupleTo(s"bus_named_${masterTLBus.busName}") {
      nodeView(slaveTLBus,p) :*= TLWidthWidget(masterTLBus.beatBytes) :*= inject(p) :*= _
      // TODO does BankBinder injection need to be  (_ :=* bb :*= _)
    }
  }
}

case class TLBusWrapperCrossFromConnection
    (xType: ClockCrossingType)
    (nodeView: (TLBusWrapper, Parameters) => TLOutwardNode = { case(w, p) => w.crossOutHelper(xType)(p) },
     inject: Parameters => TLNode = { _ => TLNameNode("temp") })
  extends TLBusWrapperConnectionLike
{
  def connect(context: HasLocations, from: Location[TLBusWrapper], to: Location[TLBusWrapper])(implicit p: Parameters): Unit = FlipRendering { implicit p =>
    val masterTLBus = context.locateTLBusWrapper(to)
    val slaveTLBus  = context.locateTLBusWrapper(from)
    masterTLBus.clockGroupNode := asyncMux(xType, context.asyncClockGroupsNode, slaveTLBus.clockGroupNode)
    slaveTLBus.coupleFrom(s"bus_named_${masterTLBus.busName}") {
      _ :=* inject(p) :=* TLWidthWidget(masterTLBus.beatBytes) :=* nodeView(masterTLBus, p)
    }
  }
}

class TLBusWrapperTopology(
  val instantiations: Seq[(Location[TLBusWrapper], TLBusWrapperInstantiationLike)],
  val connections: Seq[(Location[TLBusWrapper], Location[TLBusWrapper], TLBusWrapperConnectionLike)]
) extends CanInstantiateWithinContext with CanConnectWithinContext {
  def instantiate(context: HasLocations)(implicit p: Parameters): Unit = {
    instantiations.foreach { case (loc, params) => params.instantiate(context, loc) }
  }
  def connect(context: HasLocations)(implicit p: Parameters): Unit = {
    connections.foreach { case (from, to, params) => params.connect(context, from, to) }
  }
}

trait CanAttachTLSlaves extends HasTLBusParams { this: TLBusWrapper =>

  def toTile
      (name: Option[String] = None, buffer: BufferParams = BufferParams.none)
      (gen: => TLInwardNode): NoHandle = {
    to("tile" named name) { FlipRendering { implicit p =>
      gen :*= TLWidthWidget(beatBytes) :*= TLBuffer(buffer) :*= outwardNode
    }}
  }

  def toDRAMController[D,U,E,B <: Data]
      (name: Option[String] = None, buffer: BufferParams = BufferParams.none)
      (gen: => NodeHandle[ TLClientPortParameters,TLManagerPortParameters,TLEdgeIn,TLBundle, D,U,E,B] =
        TLNameNode(name)): OutwardNodeHandle[D,U,E,B] = {
    to("memory_controller" named name) { gen :*= TLWidthWidget(beatBytes) :*= TLBuffer(buffer) :*= outwardNode }
  }

  def toSlave[D,U,E,B <: Data]
      (name: Option[String] = None, buffer: BufferParams = BufferParams.none)
      (gen: => NodeHandle[TLClientPortParameters,TLManagerPortParameters,TLEdgeIn,TLBundle,D,U,E,B] =
        TLNameNode(name)): OutwardNodeHandle[D,U,E,B] = {
    to("slave" named name) { gen :*= TLBuffer(buffer) :*= outwardNode }
  }

  def toVariableWidthSlaveNode(name: Option[String] = None, buffer: BufferParams = BufferParams.none)(node: TLInwardNode) {
    toVariableWidthSlaveNodeOption(name, buffer)(Some(node))
  }

  def toVariableWidthSlaveNodeOption(name: Option[String] = None, buffer: BufferParams = BufferParams.none)(node: Option[TLInwardNode]) {
    node foreach { n => to("slave" named name) {
      n :*= TLFragmenter(beatBytes, blockBytes) :*= TLBuffer(buffer) :*= outwardNode
    }}
  }

  def toVariableWidthSlave[D,U,E,B <: Data]
      (name: Option[String] = None, buffer: BufferParams = BufferParams.none)
      (gen: => NodeHandle[TLClientPortParameters,TLManagerPortParameters,TLEdgeIn,TLBundle,D,U,E,B] =
        TLNameNode(name)): OutwardNodeHandle[D,U,E,B] = {
    to("slave" named name) {
      gen :*= TLFragmenter(beatBytes, blockBytes) :*= TLBuffer(buffer) :*= outwardNode
    }
  }

  def toFixedWidthSlaveNode(name: Option[String] = None, buffer: BufferParams = BufferParams.none)(gen: TLInwardNode) {
    to("slave" named name) { gen :*= TLWidthWidget(beatBytes) :*= TLBuffer(buffer) :*= outwardNode }
  }

  def toFixedWidthSlave[D,U,E,B <: Data]
      (name: Option[String] = None, buffer: BufferParams = BufferParams.none)
      (gen: => NodeHandle[TLClientPortParameters,TLManagerPortParameters,TLEdgeIn,TLBundle,D,U,E,B] =
        TLNameNode(name)): OutwardNodeHandle[D,U,E,B] = {
    to("slave" named name) { gen :*= TLWidthWidget(beatBytes) :*= TLBuffer(buffer) :*= outwardNode }
  }

  def toFixedWidthSingleBeatSlaveNode
      (widthBytes: Int, name: Option[String] = None, buffer: BufferParams = BufferParams.none)
      (gen: TLInwardNode) {
    to("slave" named name) {
      gen :*= TLFragmenter(widthBytes, blockBytes) :*= TLWidthWidget(beatBytes) :*= TLBuffer(buffer) :*= outwardNode
    }
  }

  def toFixedWidthSingleBeatSlave[D,U,E,B <: Data]
      (widthBytes: Int, name: Option[String] = None, buffer: BufferParams = BufferParams.none)
      (gen: => NodeHandle[TLClientPortParameters,TLManagerPortParameters,TLEdgeIn,TLBundle,D,U,E,B] =
        TLNameNode(name)): OutwardNodeHandle[D,U,E,B] = {
    to("slave" named name) {
      gen :*= TLFragmenter(widthBytes, blockBytes) :*= TLWidthWidget(beatBytes) :*= TLBuffer(buffer) :*= outwardNode
    }
  }

  def toLargeBurstSlave[D,U,E,B <: Data]
      (maxXferBytes: Int, name: Option[String] = None, buffer: BufferParams = BufferParams.none)
      (gen: => NodeHandle[TLClientPortParameters,TLManagerPortParameters,TLEdgeIn,TLBundle,D,U,E,B] =
        TLNameNode(name)): OutwardNodeHandle[D,U,E,B] = {
    to("slave" named name) {
      gen :*= TLFragmenter(beatBytes, maxXferBytes) :*= TLBuffer(buffer) :*= outwardNode
    }
  }

  def toFixedWidthPort[D,U,E,B <: Data]
      (name: Option[String] = None, buffer: BufferParams = BufferParams.none)
      (gen: => NodeHandle[TLClientPortParameters,TLManagerPortParameters,TLEdgeIn,TLBundle,D,U,E,B] =
        TLNameNode(name)): OutwardNodeHandle[D,U,E,B] = {
    to("port" named name) {
      gen := TLWidthWidget(beatBytes) :*= TLBuffer(buffer) :*= outwardNode
    }
  }
}

trait CanAttachTLMasters extends HasTLBusParams { this: TLBusWrapper =>
  def fromTile
      (name: Option[String], buffer: BufferParams = BufferParams.none, cork: Option[Boolean] = None)
      (gen: => TLOutwardNode): NoHandle = {
    from("tile" named name) {
      inwardNode :=* TLBuffer(buffer) :=* TLFIFOFixer(TLFIFOFixer.allVolatile) :=* gen
    }
  }

  def fromMasterNode
      (name: Option[String] = None, buffer: BufferParams = BufferParams.none)
      (gen: TLOutwardNode) {
    from("master" named name) {
      inwardNode :=* TLBuffer(buffer) :=* TLFIFOFixer(TLFIFOFixer.all) :=* gen
    }
  }

  def fromMaster[D,U,E,B <: Data]
      (name: Option[String] = None, buffer: BufferParams = BufferParams.none)
      (gen: => NodeHandle[D,U,E,B,TLClientPortParameters,TLManagerPortParameters,TLEdgeOut,TLBundle] =
        TLNameNode(name)): InwardNodeHandle[D,U,E,B] = {
    from("master" named name) {
      inwardNode :=* TLBuffer(buffer) :=* TLFIFOFixer(TLFIFOFixer.all) :=* gen
    }
  }

  def fromPort[D,U,E,B <: Data]
      (name: Option[String] = None, buffer: BufferParams = BufferParams.none)
      (gen: => NodeHandle[D,U,E,B,TLClientPortParameters,TLManagerPortParameters,TLEdgeOut,TLBundle] =
        TLNameNode(name)): InwardNodeHandle[D,U,E,B] = {
    from("port" named name) {
      inwardNode :=* TLBuffer(buffer) :=* TLFIFOFixer(TLFIFOFixer.all) :=* gen
    }
  }

  def fromCoherentMaster[D,U,E,B <: Data]
      (name: Option[String] = None, buffer: BufferParams = BufferParams.none)
      (gen: => NodeHandle[D,U,E,B,TLClientPortParameters,TLManagerPortParameters,TLEdgeOut,TLBundle] =
        TLNameNode(name)): InwardNodeHandle[D,U,E,B] = {
    from("coherent_master" named name) {
      inwardNode :=* TLBuffer(buffer) :=* TLFIFOFixer(TLFIFOFixer.all) :=* gen
    }
  }
}

trait HasTLXbarPhy { this: TLBusWrapper =>
  private val xbar = LazyModule(new TLXbar).suggestName(busName + "_xbar")

  def inwardNode: TLInwardNode = xbar.node
  def outwardNode: TLOutwardNode = xbar.node
  def busView: TLEdge = xbar.node.edges.in.head
}
