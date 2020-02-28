// See LICENSE.SiFive for license details.

package freechips.rocketchip.diplomacy

import Chisel._
import chisel3.experimental.IO
import chisel3.internal.sourceinfo.SourceInfo
import freechips.rocketchip.config.{Parameters,Field}
import freechips.rocketchip.util.HeterogeneousBag
import scala.collection.mutable.ListBuffer
import scala.util.matching._

/** Nodes system is the abstraction of the bus interconnect,
 * Since all blocks(CPUs, caches, peripheries) are connected to bus,
 * It has a very complex data flow illustrated graph below:
 *                                                  ┌────────────────────────────────────────────────────────────────────────────┐
 *                                                  ↓                                                                            │
 *                                       [[MixedNode.uoParams]]──────→[[MixedNode.mapParamsU]]───────────┐                       │
 *      [[InwardNode.accPI]]                                                     ↑                       │                       │
 *                  │                                                            │                       │                       │
 *                  ↓                                                            │                       ↓                       │
 *      [[InwardNode.iBindings]]──┐     [[MixedNode.iDirectPorts]]───→[[MixedNode.iPorts]]    [[MixedNode.uiParams]]             │
 *                  │             │                ↑                             │                       │                       │
 *                  │             │                │                             └────────┬──────────────┤                       │
 *                  │             │                │                                      │              ↓                       │
 *                  │             └────[[MixedNode.oPortMapping]]    [[MixedNode.oStar]]  │   [[MixedNode.edgesIn]]───┐          │
 *                  │                              ↑                            ↑         │              │            ↓          │
 *                  │                              │                            │         │              │ [[MixedNode.in]]      │
 *                  │                              │                            │         │              ↓            ↑          │
 *                  │                              │                            │         │   [[MixedNode.bundleIn]]──┘          │
 *                  ├─→ [[MixedNode.resolveStar]]──┼────────────────────────────┤         └────────────────────────────────────┐ │
 *                  │                              │                            │             [[MixedNode.bundleOut]]─┐        │ │
 *                  │                              │                            │                        ↑            ↓        │ │
 *                  │                              │                            │                        │ [[MixedNode.out]]   │ │
 *                  │                              ↓                            ↓                        │            ↑        │ │
 *                  │            ┌──────[[MixedNode.iPortMapping]]   [[MixedNode.iStar]]      [[MixedNode.edgesOut]]──┘        │ │
 *                  │            │                 │                                                     ↑                     │ │
 *                  │            │                 │                             ┌───────────────────────┤                     │ │
 *                  │            │                 ↓                             │                       │                     │ │
 *     [[OutwardNode.oBindings]]─┘      [[MixedNode.oDirectPorts]]───→[[MixedNode.oPorts]]    [[MixedNode.doParams]]           │ │
 *                  ↑                                                            │                       │                     │ │
 *                  │               ┌────────────────────────────────────────────┤                       │                     │ │
 *     [[OutwardNode.accPO]]        │                                            ↓                       │                     │ │
 *                                  │    [[MixedNode.diParams]]──────→[[MixedNode.mapParamsD]]───────────┘                     │ │
 *                                  │               ↑                                                                          │ │
 *                                  │               └──────────────────────────────────────────────────────────────────────────┘ │
 *                                  └────────────────────────────────────────────────────────────────────────────────────────────┘
 * It contains such phases:
 *   1. Node binding(non-lazy)
 *     `nodeB := nodeA` is a typical node binding in diplomacy.
 *     It means `nodeA` as master [[OutwardNode]], `nodeB` as slave [[InwardNode]] will be connected together.
 *     when executing `nodeB := nodeA`, `nodeB` will invoke [[InwardNode.bind]], which is implemented with [[MixedNode.bind]]:
 *       `nodeA` will call `nodeA.oPush(index, node, binding)`, in [[OutwardNode.oPush]]:
 *         `index` is current `nodeA` [[OutwardNode.accPO]] size, which represents the how many [[InwardNode]] has connected to `nodeA`
 *         `node` is [[NodeHandle]] of `NodeB`,
 *         `binding` is binding type, which is used for deciding port mapping behavior.
 *       similarly, `nodeB`, will call [[InwardNode.iPush]] after `nodeA` finish its binding.
 *   2. Port mapping(lazy)
 *     Port mapping converts [[OutwardNode.accPO]] and [[InwardNode.accPI]] to [[MixedNode.oPortMapping]] and [[MixedNode.iPortMapping]]
 *     it is implemented with [[MixedNode.resolveStar]], which solves the size of each connection.
 *   3. Parameter negotiation(lazy)
 *     After finishing port mapping,
 *     [[MixedNode.doParams]] and [[MixedNode.uiParams]] are result of parameter negotiation,
 *   4. Bundle building.(lazy)
 *     After finishing parameter propagation,
 *     [[MixedNode.edges]] can be generated by zipping ports and parameters together.
 *
 * Diplomacy has some implicit acronym described below:
 *   Parameters System:
 *   D[IO], U[IO], E[IO], B[IO] is parameters will be propagated.
 *   D: Downwards (master -> slave)
 *   U: Upwards (slave -> master)
 *   E: Edge contains necessary of nearby nodes connection information.
 *   B: Bundle is [[chisel3.Data]], which you can attach chisel data type to it.
 *
 * */


case object MonitorsEnabled extends Field[Boolean](true)
case object RenderFlipped extends Field[Boolean](false)

case class RenderedEdge(
  colour:  String,
  label:   String  = "",
  flipped: Boolean = false) // prefer to draw the arrow pointing the opposite direction of other edges

// DI = Downwards flowing Parameters received on the inner side of the node
// UI = Upwards   flowing Parameters generated by the inner side of the node
// EI = Edge Parameters describing a connection on the inner side of the node
// BI = Bundle type used when connecting to the inner side of the node
trait InwardNodeImp[DI, UI, EI, BI <: Data]
{
  def edgeI(pd: DI, pu: UI, p: Parameters, sourceInfo: SourceInfo): EI
  def bundleI(ei: EI): BI

  // Edge functions
  def monitor(bundle: BI, edge: EI) {}
  def render(e: EI): RenderedEdge

  // optional methods to track node graph
  def mixI(pu: UI, node: InwardNode[DI, UI, BI]): UI = pu // insert node into parameters
}

// DO = Downwards flowing Parameters generated by the outer side of the node
// UO = Upwards   flowing Parameters received on the outer side of the node
// EO = Edge Parameters describing a connection on the outer side of the node
// BO = Bundle type used when connecting to the outer side of the node
trait OutwardNodeImp[DO, UO, EO, BO <: Data]
{
  def edgeO(pd: DO, pu: UO, p: Parameters, sourceInfo: SourceInfo): EO
  def bundleO(eo: EO): BO

  // optional methods to track node graph
  def mixO(pd: DO, node: OutwardNode[DO, UO, BO]): DO = pd // insert node into parameters
  def getI(pd: DO): Option[BaseNode] = None // most-inward common node
}

abstract class NodeImp[D, U, EO, EI, B <: Data]
  extends Object with InwardNodeImp[D, U, EI, B] with OutwardNodeImp[D, U, EO, B]

// If your edges have the same direction, using this saves you some typing
abstract class SimpleNodeImp[D, U, E, B <: Data]
  extends NodeImp[D, U, E, E, B]
{
  def edge(pd: D, pu: U, p: Parameters, sourceInfo: SourceInfo): E
  def edgeO(pd: D, pu: U, p: Parameters, sourceInfo: SourceInfo) = edge(pd, pu, p, sourceInfo)
  def edgeI(pd: D, pu: U, p: Parameters, sourceInfo: SourceInfo) = edge(pd, pu, p, sourceInfo)
  def bundle(e: E): B
  def bundleO(e: E) = bundle(e)
  def bundleI(e: E) = bundle(e)
}

abstract class BaseNode(implicit val valName: ValName)
{
  val scope = LazyModule.scope
  val index = scope.map(_.nodes.size).getOrElse(0)
  def lazyModule = scope.get
  scope.foreach { lm => lm.nodes = this :: lm.nodes }

  val serial = BaseNode.serial
  BaseNode.serial = BaseNode.serial + 1
  protected[diplomacy] def instantiate(): Seq[Dangle]
  protected[diplomacy] def finishInstantiate(): Unit

  def name = scope.map(_.name).getOrElse("TOP") + "." + valName.name
  def omitGraphML = outputs.isEmpty && inputs.isEmpty
  lazy val nodedebugstring: String = ""

  def parents: Seq[LazyModule] = scope.map(lm => lm +: lm.parents).getOrElse(Nil)
  def context: String =
    s"$name (A $description node with parent '" +
    parents.map(_.name).mkString("/") + "' at " +
    scope.map(_.line).getOrElse("<undef>") + ")"

  def wirePrefix = {
    val camelCase = "([a-z])([A-Z])".r
    val decamel = camelCase.replaceAllIn(valName.name, _ match { case camelCase(l, h) => l + "_" + h })
    val trimNode = "_?node$".r
    val name = trimNode.replaceFirstIn(decamel.toLowerCase, "")
    if (name.isEmpty) "" else name + "_"
  }

  def description: String
  def formatNode: String = ""

  def inputs:  Seq[(BaseNode, RenderedEdge)]
  def outputs: Seq[(BaseNode, RenderedEdge)]

  protected[diplomacy] val sinkCard: Int
  protected[diplomacy] val sourceCard: Int
  protected[diplomacy] val flexes: Seq[BaseNode]
}

object BaseNode
{
  protected[diplomacy] var serial = 0
}

trait FormatEdge {
  def formatEdge: String
}

trait FormatNode[I <: FormatEdge, O <: FormatEdge] extends BaseNode {
  def edges: Edges[I,O]
  override def formatNode = {
    edges.out.map(currEdge =>
      "On Output Edge:\n\n" + currEdge.formatEdge).mkString +
    "\n---------------------------------------------\n\n" +
    edges.in.map(currEdge =>
      "On Input Edge:\n\n" + currEdge.formatEdge).mkString
  }
}

trait NoHandle
case object NoHandleObject extends NoHandle

trait NodeHandle[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data]
  extends InwardNodeHandle[DI, UI, EI, BI] with OutwardNodeHandle[DO, UO, EO, BO]
{
  // connecting two full nodes => full node
  override def :=  [DX, UX, EX, BX <: Data, EY](h: NodeHandle[DX, UX, EX, BX, DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): NodeHandle[DX, UX, EX, BX, DO, UO, EO, BO] = { bind(h, BIND_ONCE);  NodeHandle(h, this) }
  override def :*= [DX, UX, EX, BX <: Data, EY](h: NodeHandle[DX, UX, EX, BX, DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): NodeHandle[DX, UX, EX, BX, DO, UO, EO, BO] = { bind(h, BIND_STAR);  NodeHandle(h, this) }
  override def :=* [DX, UX, EX, BX <: Data, EY](h: NodeHandle[DX, UX, EX, BX, DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): NodeHandle[DX, UX, EX, BX, DO, UO, EO, BO] = { bind(h, BIND_QUERY); NodeHandle(h, this) }
  override def :*=*[DX, UX, EX, BX <: Data, EY](h: NodeHandle[DX, UX, EX, BX, DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): NodeHandle[DX, UX, EX, BX, DO, UO, EO, BO] = { bind(h, BIND_FLEX);  NodeHandle(h, this) }
  // connecting a full node with an output => an output
  override def :=  [EY](h: OutwardNodeHandle[DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): OutwardNodeHandle[DO, UO, EO, BO] = { bind(h, BIND_ONCE);  this }
  override def :*= [EY](h: OutwardNodeHandle[DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): OutwardNodeHandle[DO, UO, EO, BO] = { bind(h, BIND_STAR);  this }
  override def :=* [EY](h: OutwardNodeHandle[DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): OutwardNodeHandle[DO, UO, EO, BO] = { bind(h, BIND_QUERY); this }
  override def :*=*[EY](h: OutwardNodeHandle[DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): OutwardNodeHandle[DO, UO, EO, BO] = { bind(h, BIND_FLEX);  this }
}

object NodeHandle
{
  def apply[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](i: InwardNodeHandle[DI, UI, EI, BI], o: OutwardNodeHandle[DO, UO, EO, BO]) = new NodeHandlePair(i, o)
}

class NodeHandlePair[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data]
  (inwardHandle: InwardNodeHandle[DI, UI, EI, BI], outwardHandle: OutwardNodeHandle[DO, UO, EO, BO])
  extends NodeHandle[DI, UI, EI, BI, DO, UO, EO, BO]
{
  val inward = inwardHandle.inward
  val outward = outwardHandle.outward
  def inner = inwardHandle.inner
  def outer = outwardHandle.outer
}

trait InwardNodeHandle[DI, UI, EI, BI <: Data] extends NoHandle
{
  def inward: InwardNode[DI, UI, BI]
  def inner: InwardNodeImp[DI, UI, EI, BI]

  protected def bind[EY](h: OutwardNodeHandle[DI, UI, EY, BI], binding: NodeBinding)(implicit p: Parameters, sourceInfo: SourceInfo): Unit = inward.bind(h.outward, binding)

  // connecting an input node with a full nodes => an input node
  def :=  [DX, UX, EX, BX <: Data, EY](h: NodeHandle[DX, UX, EX, BX, DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): InwardNodeHandle[DX, UX, EX, BX] = { bind(h, BIND_ONCE);  h }
  def :*= [DX, UX, EX, BX <: Data, EY](h: NodeHandle[DX, UX, EX, BX, DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): InwardNodeHandle[DX, UX, EX, BX] = { bind(h, BIND_STAR);  h }
  def :=* [DX, UX, EX, BX <: Data, EY](h: NodeHandle[DX, UX, EX, BX, DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): InwardNodeHandle[DX, UX, EX, BX] = { bind(h, BIND_QUERY); h }
  def :*=*[DX, UX, EX, BX <: Data, EY](h: NodeHandle[DX, UX, EX, BX, DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): InwardNodeHandle[DX, UX, EX, BX] = { bind(h, BIND_FLEX);  h }
  // connecting input node with output node => no node
  def :=  [EY](h: OutwardNodeHandle[DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): NoHandle = { bind(h, BIND_ONCE);  NoHandleObject }
  def :*= [EY](h: OutwardNodeHandle[DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): NoHandle = { bind(h, BIND_STAR);  NoHandleObject }
  def :=* [EY](h: OutwardNodeHandle[DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): NoHandle = { bind(h, BIND_QUERY); NoHandleObject }
  def :*=*[EY](h: OutwardNodeHandle[DI, UI, EY, BI])(implicit p: Parameters, sourceInfo: SourceInfo): NoHandle = { bind(h, BIND_FLEX);  NoHandleObject }
}

sealed trait NodeBinding
case object BIND_ONCE  extends NodeBinding
case object BIND_QUERY extends NodeBinding
case object BIND_STAR  extends NodeBinding
case object BIND_FLEX  extends NodeBinding

trait InwardNode[DI, UI, BI <: Data] extends BaseNode
{
  private val accPI = ListBuffer[(Int, OutwardNode[DI, UI, BI], NodeBinding, Parameters, SourceInfo)]()
  private var iRealized = false

  protected[diplomacy] def iPushed = accPI.size
  protected[diplomacy] def iPush(index: Int, node: OutwardNode[DI, UI, BI], binding: NodeBinding)(implicit p: Parameters, sourceInfo: SourceInfo) {
    val info = sourceLine(sourceInfo, " at ", "")
    require (!iRealized, s"${context} was incorrectly connected as a sink after its .module was used" + info)
    accPI += ((index, node, binding, p, sourceInfo))
  }

  protected[diplomacy] lazy val iBindings = { iRealized = true; accPI.result() }

  protected[diplomacy] val iStar: Int
  protected[diplomacy] val iPortMapping: Seq[(Int, Int)]
  protected[diplomacy] def iForward(x: Int): Option[(Int, InwardNode[DI, UI, BI])] = None
  protected[diplomacy] val diParams: Seq[DI] // from connected nodes
  protected[diplomacy] val uiParams: Seq[UI] // from this node

  protected[diplomacy] def bind(h: OutwardNode[DI, UI, BI], binding: NodeBinding)(implicit p: Parameters, sourceInfo: SourceInfo): Unit
}

trait OutwardNodeHandle[DO, UO, EO, BO <: Data] extends NoHandle
{
  def outward: OutwardNode[DO, UO, BO]
  def outer: OutwardNodeImp[DO, UO, EO, BO]
}

trait OutwardNode[DO, UO, BO <: Data] extends BaseNode
{
  private val accPO = ListBuffer[(Int, InwardNode [DO, UO, BO], NodeBinding, Parameters, SourceInfo)]()
  private var oRealized = false

  protected[diplomacy] def oPushed = accPO.size
  protected[diplomacy] def oPush(index: Int, node: InwardNode [DO, UO, BO], binding: NodeBinding)(implicit p: Parameters, sourceInfo: SourceInfo) {
    val info = sourceLine(sourceInfo, " at ", "")
    require (!oRealized, s"${context} was incorrectly connected as a source after its .module was used" + info)
    accPO += ((index, node, binding, p, sourceInfo))
  }

  protected[diplomacy] lazy val oBindings = { oRealized = true; accPO.result() }

  protected[diplomacy] val oStar: Int
  protected[diplomacy] val oPortMapping: Seq[(Int, Int)]
  protected[diplomacy] def oForward(x: Int): Option[(Int, OutwardNode[DO, UO, BO])] = None
  protected[diplomacy] val uoParams: Seq[UO] // from connected nodes
  protected[diplomacy] val doParams: Seq[DO] // from this node
}

abstract class CycleException(kind: String, loop: Seq[String]) extends Exception(s"Diplomatic ${kind} cycle detected involving ${loop}")
case class StarCycleException(loop: Seq[String] = Nil) extends CycleException("star", loop)
case class DownwardCycleException(loop: Seq[String] = Nil) extends CycleException("downward", loop)
case class UpwardCycleException(loop: Seq[String] = Nil) extends CycleException("upward", loop)

case class Edges[EI, EO](in: Seq[EI], out: Seq[EO])
sealed abstract class MixedNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  val inner: InwardNodeImp [DI, UI, EI, BI],
  val outer: OutwardNodeImp[DO, UO, EO, BO])(
  implicit valName: ValName)
  extends BaseNode with NodeHandle[DI, UI, EI, BI, DO, UO, EO, BO] with InwardNode[DI, UI, BI] with OutwardNode[DO, UO, BO]
{
  val inward = this
  val outward = this

  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStar: Int, oStar: Int): (Int, Int)
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[DI]): Seq[DO]
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[UO]): Seq[UI]

  protected[diplomacy] lazy val sinkCard   = oBindings.count(_._3 == BIND_QUERY) + iBindings.count(_._3 == BIND_STAR)
  protected[diplomacy] lazy val sourceCard = iBindings.count(_._3 == BIND_QUERY) + oBindings.count(_._3 == BIND_STAR)
  protected[diplomacy] lazy val flexes     = oBindings.filter(_._3 == BIND_FLEX).map(_._2) ++
                                             iBindings.filter(_._3 == BIND_FLEX).map(_._2)
  protected[diplomacy] lazy val flexOffset = { // positive = sink cardinality; define 0 to be sink (both should work)
    def DFS(v: BaseNode, visited: Map[Int, BaseNode]): Map[Int, BaseNode] = {
      if (visited.contains(v.serial)) {
        visited
      } else {
        v.flexes.foldLeft(visited + (v.serial -> v))((sum, n) => DFS(n, sum))
      }
    }
    val flexSet = DFS(this, Map()).values
    val allSink   = flexSet.map(_.sinkCard).sum
    val allSource = flexSet.map(_.sourceCard).sum
    require (flexSet.size == 1 || allSink == 0 || allSource == 0,
      s"The nodes ${flexSet.map(_.name)} which are inter-connected by :*=* have ${allSink} :*= operators and ${allSource} :=* operators connected to them, making it impossible to determine cardinality inference direction.")
    allSink - allSource
  }

  private var starCycleGuard = false
  protected[diplomacy] lazy val (oPortMapping, iPortMapping, oStar, iStar) = {
    try {
      if (starCycleGuard) throw StarCycleException()
      starCycleGuard = true
      val oStars = oBindings.count { case (_,_,b,_,_) => b == BIND_STAR || (b == BIND_FLEX && flexOffset <  0) }
      val iStars = iBindings.count { case (_,_,b,_,_) => b == BIND_STAR || (b == BIND_FLEX && flexOffset >= 0) }
      val oKnown = oBindings.map { case (_, n, b, _, _) => b match {
        case BIND_ONCE  => 1
        case BIND_FLEX  => { if (flexOffset < 0) 0 else n.iStar }
        case BIND_QUERY => n.iStar
        case BIND_STAR  => 0 }}.foldLeft(0)(_+_)
      val iKnown = iBindings.map { case (_, n, b, _, _) => b match {
        case BIND_ONCE  => 1
        case BIND_FLEX  => { if (flexOffset >= 0) 0 else n.oStar }
        case BIND_QUERY => n.oStar
        case BIND_STAR  => 0 }}.foldLeft(0)(_+_)
      val (iStar, oStar) = resolveStar(iKnown, oKnown, iStars, oStars)
      val oSum = oBindings.map { case (_, n, b, _, _) => b match {
        case BIND_ONCE  => 1
        case BIND_FLEX  => { if (flexOffset < 0) oStar else n.iStar }
        case BIND_QUERY => n.iStar
        case BIND_STAR  => oStar }}.scanLeft(0)(_+_)
      val iSum = iBindings.map { case (_, n, b, _, _) => b match {
        case BIND_ONCE  => 1
        case BIND_FLEX  => { if (flexOffset >= 0) iStar else n.oStar }
        case BIND_QUERY => n.oStar
        case BIND_STAR  => iStar }}.scanLeft(0)(_+_)
      val oTotal = oSum.lastOption.getOrElse(0)
      val iTotal = iSum.lastOption.getOrElse(0)
      (oSum.init zip oSum.tail, iSum.init zip iSum.tail, oStar, iStar)
    } catch {
      case c: StarCycleException => throw c.copy(loop = context +: c.loop)
    }
  }

  protected[diplomacy] lazy val oDirectPorts = oBindings.flatMap { case (i, n, _, p, s) =>
    val (start, end) = n.iPortMapping(i)
    (start until end) map { j => (j, n, p, s) }
  }
  protected[diplomacy] lazy val iDirectPorts = iBindings.flatMap { case (i, n, _, p, s) =>
    val (start, end) = n.oPortMapping(i)
    (start until end) map { j => (j, n, p, s) }
  }

  // Ephemeral nodes have in_degree = out_degree
  // Thus, there must exist an Eulerian path and the below algorithms terminate
  private def oTrace(tuple: (Int, InwardNode[DO, UO, BO], Parameters, SourceInfo)): (Int, InwardNode[DO, UO, BO], Parameters, SourceInfo) =
    tuple match { case (i, n, p, s) => n.iForward(i) match {
      case None => (i, n, p, s)
      case Some ((j, m)) => oTrace((j, m, p, s))
    } }
  private def iTrace(tuple: (Int, OutwardNode[DI, UI, BI], Parameters, SourceInfo)): (Int, OutwardNode[DI, UI, BI], Parameters, SourceInfo) =
    tuple match { case (i, n, p, s) => n.oForward(i) match {
      case None => (i, n, p, s)
      case Some ((j, m)) => iTrace((j, m, p, s))
    } }
  lazy val oPorts = oDirectPorts.map(oTrace)
  lazy val iPorts = iDirectPorts.map(iTrace)

  private var oParamsCycleGuard = false
  protected[diplomacy] lazy val diParams: Seq[DI] = iPorts.map { case (i, n, _, _) => n.doParams(i) }
  protected[diplomacy] lazy val doParams: Seq[DO] = {
    try {
      if (oParamsCycleGuard) throw DownwardCycleException()
      oParamsCycleGuard = true
      val o = mapParamsD(oPorts.size, diParams)
      require (o.size == oPorts.size, s"Diplomacy error: $context has ${o.size} != ${oPorts.size} down/up outer parameters")
      o.map(outer.mixO(_, this))
    } catch {
      case c: DownwardCycleException => throw c.copy(loop = context +: c.loop)
    }
  }

  private var iParamsCycleGuard = false
  protected[diplomacy] lazy val uoParams: Seq[UO] = oPorts.map { case (o, n, _, _) => n.uiParams(o) }
  protected[diplomacy] lazy val uiParams: Seq[UI] = {
    try {
      if (iParamsCycleGuard) throw UpwardCycleException()
      iParamsCycleGuard = true
      val i = mapParamsU(iPorts.size, uoParams)
      require (i.size == iPorts.size, s"Diplomacy error: $context has ${i.size} != ${iPorts.size} up/down inner parameters")
      i.map(inner.mixI(_, this))
    } catch {
      case c: UpwardCycleException => throw c.copy(loop = context +: c.loop)
    }
  }

  protected[diplomacy] lazy val edgesOut = (oPorts zip doParams).map { case ((i, n, p, s), o) => outer.edgeO(o, n.uiParams(i), p, s) }
  protected[diplomacy] lazy val edgesIn  = (iPorts zip uiParams).map { case ((o, n, p, s), i) => inner.edgeI(n.doParams(o), i, p, s) }

  // If you need access to the edges of a foreign Node, use this method (in/out create bundles)
  lazy val edges = Edges(edgesIn, edgesOut)

  protected[diplomacy] lazy val bundleOut: Seq[BO] = edgesOut.map(e => Wire(outer.bundleO(e)))
  protected[diplomacy] lazy val bundleIn:  Seq[BI] = edgesIn .map(e => Wire(inner.bundleI(e)))

  protected[diplomacy] def danglesOut: Seq[Dangle] = oPorts.zipWithIndex.map { case ((j, n, _, _), i) =>
    Dangle(
      source = HalfEdge(serial, i),
      sink   = HalfEdge(n.serial, j),
      flipped= false,
      name   = wirePrefix + "out",
      data   = bundleOut(i))
  }
  protected[diplomacy] def danglesIn: Seq[Dangle] = iPorts.zipWithIndex.map { case ((j, n, _, _), i) =>
    Dangle(
      source = HalfEdge(n.serial, j),
      sink   = HalfEdge(serial, i),
      flipped= true,
      name   = wirePrefix + "in",
      data   = bundleIn(i))
  }

  private var bundlesSafeNow = false
  // Accessors to the result of negotiation to be used in LazyModuleImp:
  def out: Seq[(BO, EO)] = {
    require(bundlesSafeNow, s"${name}.out should only be called from the context of its module implementation")
    bundleOut zip edgesOut
  }
  def in: Seq[(BI, EI)] = {
    require(bundlesSafeNow, s"${name}.in should only be called from the context of its module implementation")
    bundleIn zip edgesIn
  }

  // Used by LazyModules.module.instantiate
  protected val identity = false
  protected[diplomacy] def instantiate() = {
    bundlesSafeNow = true
    if (!identity) {
      (iPorts zip in) foreach {
        case ((_, _, p, _), (b, e)) => if (p(MonitorsEnabled)) inner.monitor(b, e)
    } }
    danglesOut ++ danglesIn
  }

  protected[diplomacy] def finishInstantiate() = {
    bundlesSafeNow = false
  }

  // connects the outward part of a node with the inward part of this node
  protected[diplomacy] def bind(h: OutwardNode[DI, UI, BI], binding: NodeBinding)(implicit p: Parameters, sourceInfo: SourceInfo) {
    val x = this // x := y
    val y = h
    val info = sourceLine(sourceInfo, " at ", "")
    val i = x.iPushed
    val o = y.oPushed
    y.oPush(i, x, binding match {
      case BIND_ONCE  => BIND_ONCE
      case BIND_FLEX  => BIND_FLEX
      case BIND_STAR  => BIND_QUERY
      case BIND_QUERY => BIND_STAR })
    x.iPush(o, y, binding)
  }

  // meta-data for printing the node graph
  def inputs = (iPorts zip edgesIn) map { case ((_, n, p, _), e) =>
    val re = inner.render(e)
    (n, re.copy(flipped = re.flipped != p(RenderFlipped)))
  }
  def outputs = oPorts map { case (i, n, _, _) => (n, n.inputs(i)._2) }
}

abstract class MixedCustomNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  inner: InwardNodeImp [DI, UI, EI, BI],
  outer: OutwardNodeImp[DO, UO, EO, BO])(
  implicit valName: ValName)
  extends MixedNode(inner, outer)
{
  override def description = "custom"
  def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int)
  def mapParamsD(n: Int, p: Seq[DI]): Seq[DO]
  def mapParamsU(n: Int, p: Seq[UO]): Seq[UI]
}

abstract class CustomNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(
  implicit valName: ValName)
  extends MixedCustomNode(imp, imp)

class MixedJunctionNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  inner: InwardNodeImp [DI, UI, EI, BI],
  outer: OutwardNodeImp[DO, UO, EO, BO])(
  uRatio: Int,
  dRatio: Int,
  dFn: Seq[DI] => Seq[DO],
  uFn: Seq[UO] => Seq[UI])(
  implicit valName: ValName)
  extends MixedNode(inner, outer)
{
  require (dRatio >= 1)
  require (uRatio >= 1)

  override def description = "junction"
  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (iStars <= uRatio, s"$context appears left of a :*= $iStars times; at most $uRatio (uRatio) is allowed")
    require (oStars <= dRatio, s"$context appears right of a :=* $oStars times; at most $dRatio (dRatio) is allowed")
    val iFixed = uRatio - iStars
    val oFixed = dRatio - oStars
    require (oFixed != 0 || iFixed != 0, s"$context has flexible bindings for every in/out ratio; it cannot resolve the cardinality")
    require (oFixed == 0 || oKnown % oFixed == 0, s"$context is connected to $oKnown fixed outputs, but this must be divisible by the inflexible ratio $oFixed")
    require (iFixed == 0 || iKnown % iFixed == 0, s"$context is connected to $iKnown fixed inputs, but this must be divisible by the inflexible ratio $iFixed")
    require (iFixed == 0 || oFixed == 0 || oKnown/oFixed == iKnown/iFixed, s"$context has ${oKnown/oFixed} output multiplicity, which does not equal the ${iKnown/iFixed} input multiplicity")
    val multiplier = if (iFixed == 0) { oKnown/oFixed } else { iKnown/iFixed }
    (multiplier, multiplier)
  }
  private def wrapDFn(in: Seq[DI]): Seq[DO] = {
    val out = dFn(in)
    require (in.size  == uRatio, s"$context has the wrong number of parameters in for dFn (${in.size} != ${uRatio})")
    require (out.size == dRatio, s"$context has the wrong number of parameters out of dFn (${out.size} != ${dRatio})")
    out
  }
  private def wrapUFn(out: Seq[UO]): Seq[UI] = {
    val in = uFn(out)
    require (in.size  == uRatio, s"$context has the wrong number of parameters out of uFn (${in.size} != ${uRatio})")
    require (out.size == dRatio, s"$context has the wrong number of parameters in for uFn (${out.size} != ${dRatio})")
    in
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[DI]): Seq[DO] =
    p.grouped(p.size/uRatio).toList.transpose.map(wrapDFn).transpose.flatten
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[UO]): Seq[UI] =
    p.grouped(p.size/dRatio).toList.transpose.map(wrapUFn).transpose.flatten

  def inoutGrouped: Seq[(Seq[(BI, EI)], Seq[(BO, EO)])] = {
    val iGroups = in .grouped(in .size/uRatio).toList.transpose
    val oGroups = out.grouped(out.size/dRatio).toList.transpose
    iGroups zip oGroups
  }
}

class JunctionNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(
  uRatio: Int,
  dRatio: Int,
  dFn: Seq[D] => Seq[D],
  uFn: Seq[U] => Seq[U])(
  implicit valName: ValName)
    extends MixedJunctionNode[D, U, EI, B, D, U, EO, B](imp, imp)(uRatio, dRatio, dFn, uFn)

class MixedAdapterNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  inner: InwardNodeImp [DI, UI, EI, BI],
  outer: OutwardNodeImp[DO, UO, EO, BO])(
  dFn: DI => DO,
  uFn: UO => UI)(
  implicit valName: ValName)
  extends MixedNode(inner, outer)
{
  override def description = "adapter"
  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (oStars + iStars <= 1, s"$context appears left of a :*= $iStars times and right of a :=* $oStars times; at most once is allowed")
    if (oStars > 0) {
      require (iKnown >= oKnown, s"$context has $oKnown outputs and $iKnown inputs; cannot assign ${iKnown-oKnown} edges to resolve :=*")
      (0, iKnown - oKnown)
    } else if (iStars > 0) {
      require (oKnown >= iKnown, s"$context has $oKnown outputs and $iKnown inputs; cannot assign ${oKnown-iKnown} edges to resolve :*=")
      (oKnown - iKnown, 0)
    } else {
      require (oKnown == iKnown, s"$context has $oKnown outputs and $iKnown inputs; these do not match")
      (0, 0)
    }
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[DI]): Seq[DO] = {
    require(n == p.size, s"$context has ${p.size} inputs and ${n} outputs; they must match")
    p.map(dFn)
  }
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[UO]): Seq[UI] = {
    require(n == p.size, s"$context has ${n} inputs and ${p.size} outputs; they must match")
    p.map(uFn)
  }
}

class AdapterNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(
  dFn: D => D,
  uFn: U => U)(
  implicit valName: ValName)
    extends MixedAdapterNode[D, U, EI, B, D, U, EO, B](imp, imp)(dFn, uFn)

// IdentityNodes automatically connect their inputs to outputs
class IdentityNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])()(implicit valName: ValName)
  extends AdapterNode(imp)({ s => s }, { s => s })
{
  override def description = "identity"
  protected override val identity = true
  override protected[diplomacy] def instantiate() = {
    val dangles = super.instantiate()
    (out zip in) map { case ((o, _), (i, _)) => o <> i }
    dangles
  } 
}

// EphemeralNodes disappear from the final node graph
class EphemeralNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])()(implicit valName: ValName)
  extends AdapterNode(imp)({ s => s }, { s => s })
{
  override def description = "ephemeral"
  override def omitGraphML = true
  override def oForward(x: Int) = Some(iDirectPorts(x) match { case (i, n, _, _) => (i, n) })
  override def iForward(x: Int) = Some(oDirectPorts(x) match { case (i, n, _, _) => (i, n) })
  override protected[diplomacy] def instantiate() = Nil
}

class MixedNexusNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  inner: InwardNodeImp [DI, UI, EI, BI],
  outer: OutwardNodeImp[DO, UO, EO, BO])(
  dFn: Seq[DI] => DO,
  uFn: Seq[UO] => UI,
  // no inputs and no outputs is always allowed
  inputRequiresOutput: Boolean = true,
  outputRequiresInput: Boolean = true)(
  implicit valName: ValName)
  extends MixedNode(inner, outer)
{
  override def description = "nexus"
  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    // a nexus treats :=* as a weak pointer
    require (!outputRequiresInput || oKnown == 0 || iStars + iKnown != 0, s"$context has $oKnown required outputs and no possible inputs")
    require (!inputRequiresOutput || iKnown == 0 || oStars + oKnown != 0, s"$context has $iKnown required inputs and no possible outputs")
    if (iKnown == 0 && oKnown == 0) (0, 0) else (1, 1)
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[DI]): Seq[DO] = { if (n > 0) { val a = dFn(p); Seq.fill(n)(a) } else Nil }
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[UO]): Seq[UI] = { if (n > 0) { val a = uFn(p); Seq.fill(n)(a) } else Nil }
}

class NexusNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(
  dFn: Seq[D] => D,
  uFn: Seq[U] => U,
  inputRequiresOutput: Boolean = true,
  outputRequiresInput: Boolean = true)(
  implicit valName: ValName)
    extends MixedNexusNode[D, U, EI, B, D, U, EO, B](imp, imp)(dFn, uFn, inputRequiresOutput, outputRequiresInput)

// There are no Mixed SourceNodes
class SourceNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(po: Seq[D])(implicit valName: ValName)
  extends MixedNode(imp, imp)
{
  override def description = "source"
  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (oStars <= 1, s"$context appears right of a :=* ${oStars} times; at most once is allowed")
    require (iStars == 0, s"$context cannot appear left of a :*=")
    require (iKnown == 0, s"$context cannot appear left of a :=")
    require (po.size == oKnown || oStars == 1, s"$context has only ${oKnown} outputs connected out of ${po.size}")
    require (po.size >= oKnown, s"$context has ${oKnown} outputs out of ${po.size}; cannot assign ${po.size - oKnown} edges to resolve :=*")
    (0, po.size - oKnown)
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[D]): Seq[D] = po
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[U]): Seq[U] = Seq()

  def makeIOs()(implicit valName: ValName): HeterogeneousBag[B] = {
    val bundles = this.out.map(_._1)
    val ios = IO(Flipped(new HeterogeneousBag(bundles.map(_.cloneType))))
    ios.suggestName(valName.name)
    bundles.zip(ios).foreach { case (bundle, io) => bundle <> io }
    ios
  }
}

// There are no Mixed SinkNodes
class SinkNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(pi: Seq[U])(implicit valName: ValName)
  extends MixedNode(imp, imp)
{
  override def description = "sink"
  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (iStars <= 1, s"$context appears left of a :*= ${iStars} times; at most once is allowed")
    require (oStars == 0, s"$context cannot appear right of a :=*")
    require (oKnown == 0, s"$context cannot appear right of a :=")
    require (pi.size == iKnown || iStars == 1, s"$context has only ${iKnown} inputs connected out of ${pi.size}")
    require (pi.size >= iKnown, s"$context has ${iKnown} inputs out of ${pi.size}; cannot assign ${pi.size - iKnown} edges to resolve :*=")
    (pi.size - iKnown, 0)
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[D]): Seq[D] = Seq()
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[U]): Seq[U] = pi

  def makeIOs()(implicit valName: ValName): HeterogeneousBag[B] = {
    val bundles = this.in.map(_._1)
    val ios = IO(new HeterogeneousBag(bundles.map(_.cloneType)))
    ios.suggestName(valName.name)
    bundles.zip(ios).foreach { case (bundle, io) => io <> bundle }
    ios
  }
}

class MixedTestNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data] protected[diplomacy](
  node: NodeHandle [DI, UI, EI, BI, DO, UO, EO, BO], clone: CloneLazyModule)(
  implicit valName: ValName)
  extends MixedNode(node.inner, node.outer)
{
  // The devices connected to this test node must recreate these parameters:
  def iParams: Seq[DI] = node.inward .diParams
  def oParams: Seq[UO] = node.outward.uoParams

  override def description = "test"
  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (oStars <= 1, s"$context appears right of a :=* $oStars times; at most once is allowed")
    require (iStars <= 1, s"$context appears left of a :*= $iStars times; at most once is allowed")
    require (node.inward .uiParams.size == iKnown || iStars == 1, s"$context has only $iKnown inputs connected out of ${node.inward.uiParams.size}")
    require (node.outward.doParams.size == oKnown || oStars == 1, s"$context has only $oKnown outputs connected out of ${node.outward.doParams.size}")
    (node.inward.uiParams.size - iKnown, node.outward.doParams.size - oKnown)
  }

  protected[diplomacy] def mapParamsU(n: Int, p: Seq[UO]): Seq[UI] = node.inward .uiParams
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[DI]): Seq[DO] = node.outward.doParams

  override protected[diplomacy] def instantiate() = {
    val dangles = super.instantiate()
    val orig_module = clone.base.module
    val clone_auto = clone.io("auto").asInstanceOf[AutoBundle]

    danglesOut.zipWithIndex.foreach { case (d, i) =>
      val orig = orig_module.dangles.find(_.source == HalfEdge(node.outward.serial, i))
      require (orig.isDefined, s"Cloned node ${node.outward.name} must be connected externally out ${orig_module.name}")
      val io_name = orig_module.auto.elements.find(_._2 eq orig.get.data).get._1
      d.data <> clone_auto.elements(io_name)
    }
    danglesIn.zipWithIndex.foreach { case (d, i) =>
      val orig = orig_module.dangles.find(_.sink == HalfEdge(node.inward.serial, i))
      require (orig.isDefined, s"Cloned node ${node.inward.name} must be connected externally in ${orig_module.name}")
      val io_name = orig_module.auto.elements.find(_._2 eq orig.get.data).get._1
      clone_auto.elements(io_name) <> d.data
    }

    dangles
  }
}
