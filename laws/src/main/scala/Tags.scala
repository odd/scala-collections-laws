package laws

import scala.language.implicitConversions

/** Information that you can use to filter tests (note: only flags are used for compile-time generation) */
trait TestInfo {
  def hasZero: Boolean
  def isSymOp: Boolean
  def flags: Set[String]
  def extra: TestInfo.Extra = TestInfo.Empty
  final def runtimeElt: java.lang.Class[_] = {
    val r = runtimeElt
    if      (r == classOf[java.lang.Integer])       classOf[Int]
    else if (r == classOf[java.lang.Long])          classOf[Long]
    else if (r == classOf[java.lang.Double])        classOf[Double]
    else if (r == classOf[java.lang.Float])         classOf[Float]
    else if (r == classOf[java.lang.Character])     classOf[Char]
    else if (r == classOf[java.lang.Byte])          classOf[Byte]
    else if (r == classOf[java.lang.Short])         classOf[Short]
    else if (r == classOf[scala.runtime.BoxedUnit]) classOf[Unit]
    else                                            r
  }
  def boxedRuntime: java.lang.Class[_]
  def runtimeColl: java.lang.Class[_]
}
object TestInfo {
  /** Trait to mark any extra data that one might need to pass and pattern-match out. */
  sealed trait Extra {}

  /** Case where no extra data is needed. */
  final case object Empty extends Extra {}
}

/** Tags provide a way to select which laws are applicable for a given run.  For instance,
  * if you are testing collections with `Int`s and with `String`s, some tests may be
  * specific to the collection type; in that case you would tag the appropriate laws
  * using a string that helps you distinguish them. (E.g. `Tags("Int")`.)
  *
  * Additional restriction of the set may be achieved by including tests in `select`.  All
  * such tests must pass for a particular set of parameters to be included in a test.
  */
case class Tags(positive: Set[String], negative: Set[String], select: Vector[TestInfo => Boolean]) {
  /** Tests whether any tags are present (either boolean or string-valued) */
  val isEmpty = positive.isEmpty && negative.isEmpty && select.isEmpty

  /** Checks whether a certain set of flags is compatible for code generation (compile-time compatible) */
  def compatible(flags: Set[String]) =
    positive.forall(flags contains _) && !flags.exists(negative contains _)

  /** Checks whether a certain choice of parameters is suitable for testing (runtime compatible) */
  def validate(t: TestInfo) = compatible(t.flags) && select.forall(p => p(t))

  /** Sets a boolean tag that must be present */
  def need(s: String): Tags =
    if (positive contains s) this
    else if (negative contains s) new Tags(positive + s, negative - s, select)
    else new Tags(positive + s, negative, select)

  /** Requires that a particular tag be absent */
  def shun(s: String): Tags =
    if (negative contains s) this
    else if (positive contains s) new Tags(positive - s, negative + s, select)
    else new Tags(positive, negative + s, select)

  def filter(p: TestInfo => Boolean): Tags = new Tags(positive, negative, select :+ p)

  override lazy val toString = {
    val named = positive.toList.sorted ::: negative.toList.map("!" + _).sorted
    val pred  = select.length match {
      case 0 => ""
      case 1 => "(1 filter)"
      case n => f"($n filters)"
    }
    (if (pred.nonEmpty) named.toVector :+ pred else named.toVector).mkString(" ")
  }
}
object Tags {
  /** Taggish represents values that can be tags: either a key alone, or a key-value pair (all strings). */
  sealed trait Taggish {}
  final case class PosTag(tag: String) extends Taggish {}
  final case class NegTag(tag: String) extends Taggish {}
  final case class SelectTag(p: TestInfo => Boolean) extends Taggish {}
  //final case class SelectTag(p: List[String] => Boolean) extends Taggish {}
  /** Implicits contains implicit conversions to values that can be tags. */
  object Implicits {
    implicit class StringIsTaggish(s: String) {
      def y: PosTag = PosTag(s)
      def n: NegTag = NegTag(s)
    }
    implicit def stringIsPositiveByDefault(s: String): PosTag = PosTag(s)
    implicit def select(p: TestInfo => Boolean): SelectTag = SelectTag(p)
  }

  /** Canonical empty set of tags. (That is, no tags.) */
  val empty = new Tags(Set.empty[String], Set.empty[String], Vector.empty[TestInfo => Boolean])

  /** Create a mixed set of boolean and predicate tags.
    *
    * First, `import laws.Tags.Implicits._`.  Then use `"seq".y, "set".n, select(_.hasZero)` to set,
    * in this example, a tag that must be present, mustn't be present, and a test that must pass, respectively.
    */
  def apply(key: Taggish, keys: Taggish*) = {
    val all = key :: keys.toList
    val positive = all.collect{ case PosTag(s)    => s }.toSet
    new Tags(
      positive,
      all.collect{ case NegTag(s)    => s }.toSet &~ positive,
      all.collect{ case SelectTag(p) => p }.toVector
    )
  }
}