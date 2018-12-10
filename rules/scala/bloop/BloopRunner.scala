package annex.bloop

import higherkindness.rules_scala.common.worker.WorkerMain
import bloop.Bloop

object BloopRunner extends WorkerMain[Unit] {
  override def init(args: Option[Array[String]]): Unit = ()
  override def work(ctx: Unit, args: Array[String]): Unit = Bloop
}
