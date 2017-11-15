package laws

import scala.language.higherKinds

import scala.reflect.runtime.universe.TypeTag


/** Provides a source for individual instances we will test.
  * The variable names used are ones we can use to select
  * tests inside the generator.
  *
  * Collections that can take any generic type go in here.  Collections that can only
  * take certain types go in more specific subclasses (or, in the case of maps, an alternate trait) below.
  */
abstract class InstantiatorsOf[A]
extends Exploratory[(A, Array[A], Array[A])] {
  import Flag._

  protected implicit def orderingOfA: Ordering[A]   // Require some kind of ordering (even a dumb one) for all element types
  protected implicit def typeTagA: TypeTag[A]       // TypeTag that gives us information about the element

  protected def allFlags: Array[Flag]
  protected val inst = Instance.flagged[A](allFlags: _*)   // Add all the flags specified in `allFlags`

  // Ways to get sizes of different kinds of collections
  protected implicit def sizeOfSeq[A, S[A] <: collection.Seq[A]] = new Sizable[S[A]] { def sizeof(s: S[A]) = s.length }
  protected implicit def sizeOfOnce[A, O[A] <: collection.Traversable[A]] = new Sizable[O[A]] { def sizeof(o: O[A]) = o.size }
  protected implicit def sizeOfArray[A] = new Sizable[Array[A]] { def sizeof(a: Array[A]) = a.length }
  protected implicit val sizeOfString = new Sizable[String] { def sizeof(s: String) = s.length }

  trait Deployed[A, CC] extends Function0[Instance.FromArray[A, CC]] with Instance.Deployed { self =>
    def secretly: Instance.FromArray[A, CC]

    // Forwarder that allows us to add a source of enriched methods
    def moreMethods(mc: MethodChecker): Deployed[A, CC] = new Deployed[A, CC] {
      def accesses = self.accesses
      def name = self.name
      def group = self.group
      override def path = self.path
      def apply() = self.apply().moreMethods(mc)
      def secretly = self.secretly.moreMethods(mc)
    }
  }

  protected val registry = Vector.newBuilder[Deployed[A, _]]

  object Imm extends Instance.PackagePath {
    def nickname = "Imm"
    def fullyQualified = "scala.collection.immutable"
    def C[CC: TypeTag: Sizable](ccf: Array[A] => CC, flags: Flag*)(implicit nm: sourcecode.Name): Deployed[A, CC] = {
      val gen = inst.cacheWith(ccf, flags: _*)(nm, implicitly[TypeTag[CC]], implicitly[Sizable[CC]])
      val ans = new Deployed[A, CC]{
        val secretly = gen
        var accesses: Int = 0
        val name = nm.value.toString
        def group = typeTagA.tpe.toString + " in " + nickname
        def apply(): Instance.FromArray[A, CC] = { accesses += 1; secretly }
      }
      registry += ans
      ans
    }

    // MUST use lower-camel-cased collection class name for code generator to work properly!
    val hashSet     = C(_.to[collection.immutable.HashSet], SET)
    val indexedSeq  = C(_.to[collection.immutable.IndexedSeq], SEQ)
    val iterable    = C(_.to[collection.immutable.Iterable])
    val linearSeq   = C(_.to[collection.immutable.LinearSeq], SEQ)
    val list        = C(_.toList, SEQ)
    val queue       = C(_.to[collection.immutable.Queue], SEQ)
    val seq         = C(_.to[collection.immutable.Seq], SEQ)
    val set         = C(_.toSet, SET)
    val sortedSet   = C(_.to[collection.immutable.SortedSet], SET, SUPER_ON_ZIP)
    val stream      = C(_.to[Stream], SEQ)
    val traversable = C(_.to[collection.immutable.Traversable])
    val treeSet     = C(_.to[collection.immutable.TreeSet], SET, SUPER_ITREES, SUPER_ON_ZIP)
    val vector      = C(_.toVector, SEQ)
  }

  object Mut extends Instance.PackagePath {
    def nickname = "Mut"
    def fullyQualified = "scala.collection.mutable"
    def C[CC: TypeTag: Sizable](ccf: Array[A] => CC, flags: Flag*)(implicit nm: sourcecode.Name): Deployed[A, CC] = {
      val gen = inst.makeWith(ccf, flags: _*)(nm, implicitly[TypeTag[CC]], implicitly[Sizable[CC]])
      val ans = new Deployed[A, CC]{
        val secretly = gen
        var accesses: Int = 0
        val name = nm.value.toString
        def group = typeTagA.tpe.toString + " in " + nickname
        def apply(): Instance.FromArray[A, CC] = { accesses += 1; secretly }
      }
      registry += ans
      ans
    }

    // MUST use lower-camel-cased collection class name for code generator to work properly!
    val array        = C(_.clone, SEQ, ARRAY).moreMethods(MethodChecker.from[collection.mutable.ArrayOps[A]])
    val arrayBuffer  = C(_.to[collection.mutable.ArrayBuffer], SEQ)
    val arraySeq     = C(_.to[collection.mutable.ArraySeq], SEQ)
    val arrayStack   = C(_.to[collection.mutable.ArrayStack], SEQ, ARRAYSTACK_ADDS_ON_FRONT)
    val buffer       = C(_.to[collection.mutable.Buffer], SEQ)
    val hashSet      = C(_.to[collection.mutable.HashSet], SET)
    val indexedSeq   = C(_.to[collection.mutable.IndexedSeq], SEQ)
    val iterable     = C(_.to[collection.mutable.Iterable])
    val linearSeq    = C(_.to[collection.mutable.LinearSeq], SEQ)
    val linkedHashSet= C(_.to[collection.mutable.LinkedHashSet], SET)
    val listBuffer   = C(_.to[collection.mutable.ListBuffer], SEQ)
    val priorityQueue= C(_.to[collection.mutable.PriorityQueue], SUPER_ON_ZIP, PRIORITYQUEUE_IS_SPECIAL)
    val queue        = C(_.to[collection.mutable.Queue], SEQ)
    val seq          = C(_.to[collection.mutable.Seq], SEQ)
    val treeSet      = C(_.to[collection.mutable.TreeSet], SET, SUPER_ON_ZIP)
    // val unrolledBuffer = C(_.to[collection.mutable.UnrolledBuffer], SEQ)
    val wrappedArray = C(_.clone: collection.mutable.WrappedArray[A], SEQ)
  }

  def possible_a: Array[A]
  def possible_x: Array[Array[A]]
  def possible_y: Array[Array[A]]

  val sizes = Array(possible_a.length, possible_x.length, possible_y.length)
  
  def lookup(ixs: Array[Int]): Option[(A, Array[A], Array[A])] =
    if (!validate(ixs)) None
    else Some((possible_a(ixs(0)), possible_x(ixs(1)), possible_y(ixs(2))))

  def force(): Any  // Makes sure all the objects that are used are loaded.

  lazy val all = {
    force()
    registry.result
  }
}

/** Instantiators for map types where both keys and values can be anything.
  *
  * Maps with restrictions on keys or values go in more specific subclasses below.
  */
trait InstantiatorsOfKV[K, V] extends Exploratory[((K, V), Array[(K, V)], Array[(K, V)])] { self: InstantiatorsOf[(K, V)] =>
  import Flag._

  protected implicit def orderingOfK: Ordering[K]

  protected implicit def typeTagK: TypeTag[K]
  protected implicit def typeTagV: TypeTag[V]
  protected val kvInst = Instance.flagged[(K, V)](allFlags: _*)
  protected implicit def sizeOfMap[K, V, M[K, V] <: collection.Map[K, V]] = new Sizable[M[K, V]] { def sizeof(m: M[K, V]) = m.size }

  object ImmKV extends Instance.PackagePath {
    def nickname = "ImmKV"
    def fullyQualified = "scala.collection.immutable"
    def C[CC: TypeTag: Sizable](ccf: Array[(K, V)] => CC, flags: Flag*)(implicit nm: sourcecode.Name): Deployed[(K, V), CC] = {
      val gen = kvInst.cacheWith(ccf, (MAP +: flags): _*)(nm, implicitly[TypeTag[CC]], implicitly[Sizable[CC]])
      val ans = new Deployed[(K, V), CC]{
        val secretly = gen
        var accesses: Int = 0
        val name = nm.value.toString
        def group = typeTagA.tpe.toString + " in " + nickname
        def apply(): Instance.FromArray[(K, V), CC] = { accesses += 1; secretly }
      }
      registry += ans
      ans
    }

    // MUST use lower-camel-cased collection class name for code generator to work properly!
    val hashMap =   C({ a => val mb = collection.immutable.HashMap.newBuilder[K, V];   for (kv <- a) mb += kv; mb.result }, SUPER_IHASHM)
    val listMap =   C({ a => val mb = collection.immutable.ListMap.newBuilder[K, V];   for (kv <- a) mb += kv; mb.result })
    val sortedMap = C({ a => val mb = collection.immutable.SortedMap.newBuilder[K, V]; for (kv <- a) mb += kv; mb.result })
    val treeMap =   C({ a => val mb = collection.immutable.TreeMap.newBuilder[K, V];   for (kv <- a) mb += kv; mb.result })
  }

  object MutKV extends Instance.PackagePath {
    def nickname = "MutKV"
    def fullyQualified = "scala.collection.mutable"
    def C[CC: TypeTag: Sizable](ccf: Array[(K, V)] => CC, flags: Flag*)(implicit nm: sourcecode.Name): Deployed[(K, V), CC] = {
      val gen = kvInst.makeWith(ccf, (MAP +: flags): _*)(nm, implicitly[TypeTag[CC]], implicitly[Sizable[CC]])
      val ans = new Deployed[(K, V), CC]{
        val secretly = gen
        var accesses: Int = 0
        val name = nm.value.toString
        def group = typeTagA.tpe.toString + " in " + nickname
        def apply(): Instance.FromArray[(K, V), CC] = { accesses += 1; secretly }
      }
      registry += ans
      ans
    }

    // MUST use lower-camel-cased collection class name for code generator to work properly!
    val hashMap =       C({ a => val m = new collection.mutable.HashMap[K, V];       for (kv <- a) m += kv; m }, SUPER_MXMAP)
    val listMap =       C({ a => val m = new collection.mutable.ListMap[K, V];       for (kv <- a) m += kv; m }, SUPER_MXMAP)
    val linkedHashMap = C({ a => val m = new collection.mutable.LinkedHashMap[K, V]; for (kv <- a) m += kv; m }, SUPER_MXMAP)
    val openHashMap =   C({ a => val m = new collection.mutable.OpenHashMap[K, V];   for (kv <- a) m += kv; m }, SUPER_MXMAP, SUPER_MOPENHM)
    val sortedMap =     C({ a => val m = collection.mutable.SortedMap.empty[K, V];   for (kv <- a) m += kv; m })
    val treeMap =       C({ a => val m = new collection.mutable.TreeMap[K, V];       for (kv <- a) m += kv; m }, SUPER_MXMAP)
    val weakHashMap =   C({ a => val m = new collection.mutable.WeakHashMap[K, V];   for (kv <- a) m += kv; m }, SUPER_MXMAP)
  }
}

object OrderingSource {
  val orderingOfLong = implicitly[Ordering[Long]]
  val orderingOfInt = implicitly[Ordering[Int]]
  val orderingOfString = implicitly[Ordering[String]]
  val orderingOfLongString = implicitly[Ordering[(Long, String)]]
  val orderingOfStringLong = implicitly[Ordering[(String, Long)]]
}

object TypeTagSource {
  val typeTagInt = implicitly[TypeTag[Int]]
  val typeTagLong = implicitly[TypeTag[Long]]
  val typeTagString = implicitly[TypeTag[String]]
  val typeTagLongString = implicitly[TypeTag[(Long, String)]]
  val typeTagStringLong = implicitly[TypeTag[(String, Long)]]
}

object InstantiatorsOfInt extends InstantiatorsOf[Int] {
  import Flag._

  protected implicit def orderingOfA = OrderingSource.orderingOfInt
  protected implicit def typeTagA = TypeTagSource.typeTagInt
  protected def allFlags = Array(INT)

  protected implicit val sizeOfRange = new Sizable[collection.immutable.Range] { def sizeof(r: collection.immutable.Range) = r.size }
  protected implicit val sizeOfIBitSet = new Sizable[collection.immutable.BitSet] { def sizeof(s: collection.immutable.BitSet) = s.size }
  protected implicit val sizeOfMBitSet = new Sizable[collection.mutable.BitSet] { def sizeof(s: collection.mutable.BitSet) = s.size }

  object ImmInt extends Instance.PackagePath {
    // If we have other (String, _) types, move this out into a trait
    def nickname = "ImmInt"
    def fullyQualified = "scala.collection.immutable"
    def C[CC: TypeTag: Sizable](ccf: Array[Int] => CC, flags: Flag*)(implicit nm: sourcecode.Name): Deployed[Int, CC] = {
      val gen = inst.cacheWith(ccf, flags: _*)(nm, implicitly[TypeTag[CC]], implicitly[Sizable[CC]])
      val ans = new Deployed[Int, CC]{
        val secretly = gen
        var accesses: Int = 0
        val name = nm.value.toString
        def group = typeTagA.tpe.toString + " in " + nickname
        def apply(): Instance.FromArray[Int, CC] = { accesses += 1; secretly }
      }
      registry += ans
      ans
    }

    // MUST use lower-camel-cased collection class name for code generator to work properly!
    val bitSet = C(
      { a => val b = collection.immutable.BitSet.newBuilder; a.foreach{ x => if (x >= 0) b += x }; b.result },
      SET, SUPER_ON_ZIP, BITSET_MAP_BREAKS_BOUNDS
    )
    //val range = C({ a => if (a.length % 3 == 0) 0 until a.length else 0 to a.length })
  }
  object MutInt extends Instance.PackagePath {
    // If we have other (String, _) types, move this out into a trait
    def nickname = "MutInt"
    def fullyQualified = "scala.collection.mutable"
    def C[CC: TypeTag: Sizable](ccf: Array[Int] => CC, flags: Flag*)(implicit nm: sourcecode.Name): Deployed[Int, CC] = {
      val gen = inst.makeWith(ccf, flags: _*)(nm, implicitly[TypeTag[CC]], implicitly[Sizable[CC]])
      val ans = new Deployed[Int, CC]{
        val secretly = gen
        var accesses: Int = 0
        val name = nm.value.toString
        def group = typeTagA.tpe.toString + " in " + nickname
        def apply(): Instance.FromArray[Int, CC] = { accesses += 1; secretly }
      }
      registry += ans
      ans
    }

    // MUST use lower-camel-cased collection class name for code generator to work properly!
    val bitSet = C(
      { a => val b = new collection.mutable.BitSet; a.foreach{ x => if (x >= 0) b += x }; b },
      SET, SUPER_ON_ZIP, BITSET_MAP_BREAKS_BOUNDS
    )
  }

  lazy val possible_a = Array(0, 1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 23, 31, 47, 152, 3133, 1294814, -1, -2, -6, -19, -1915, -19298157)
  lazy val possible_x = Array(
    Array.empty[Int],
    Array(0),
    Array(125),
    Array(-15),
    Array(0, 1),
    Array(0, 1, 2),
    Array(0, 1, 2, 3),
    Array(0, 1, 2, 3, 4),
    Array(4, 4, 4, 4, 4),
    Array(0, 1, 2, 3, 4, 5, 6),
    Array(0, 1, 2, 3, 4, 5, 6, 7),
    Array(0, 1, 2, 3, 4, 5, 6, 7, 8),
    Array(10, 10, 10, 10, 10, 5, 5, 5, 5, 1, 1, 1),
    Array(0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1),
    Array.range(0,31),
    Array.range(0,32),
    Array.range(0,33),
    Array.range(0,192),
    Array.fill(254)(42),
    Array.range(0,811)
  )
  lazy val possible_y = possible_x

  /** This is important!  This registers the collections that you actually want to have available! */
  val force = Imm :: Mut :: ImmInt :: MutInt :: Nil
}

object InstantiatorsOfStr extends InstantiatorsOf[String] {
  import Flag._

  protected implicit def orderingOfA = OrderingSource.orderingOfString
  protected implicit def typeTagA = TypeTagSource.typeTagString
  protected def allFlags = Array(STR)

  lazy val possible_a = Array(
    "", "0", "one", "salmon", "\u0000\u0000\u0000\u0000", "the quick brown fox jumps over the lazy dog", "\u1517\u1851..!"
  )
  lazy val possible_x = Array(
    Array.empty[String],
    Array(possible_a(1)),
    Array(possible_a(3)),
    possible_a,
    Array("0", "1", "0", "1", "0", "1", "0", "1", "0", "1", "0", "1"),
    Array.range(-44, 45).map(_.toString),
    Array.fill(184)("herring")
  )
  lazy val possible_y = possible_x

  /** This is important!  This registers the collections that you actually want to have available! */
  val force = Imm :: Mut :: Nil
}

object InstantiatorsOfLongStr extends InstantiatorsOf[(Long, String)] with InstantiatorsOfKV[Long, String] {
  import Flag._

  protected implicit def orderingOfA = OrderingSource.orderingOfLongString
  protected implicit def orderingOfK = OrderingSource.orderingOfLong
  protected implicit def typeTagA = TypeTagSource.typeTagLongString
  protected implicit def typeTagK = TypeTagSource.typeTagLong
  protected implicit def typeTagV = TypeTagSource.typeTagString
  protected def allFlags = Array[Flag]()

  protected implicit val sizeOfLongMap_Long_String = 
    new Sizable[collection.mutable.LongMap[String]] { 
      def sizeof(m: collection.mutable.LongMap[String]) = m.size 
    }

  object MutLongV extends Instance.PackagePath {
    // If we have other (String, _) types, move this out into a trait
    def nickname = "MutLongV"
    def fullyQualified = "scala.collection.mutable"
    def C[CC: TypeTag: Sizable](ccf: Array[(Long, String)] => CC, flags: Flag*)(implicit nm: sourcecode.Name): Deployed[(Long, String), CC] = {
      val gen = kvInst.makeWith(ccf, (MAP +: flags): _*)(nm, implicitly[TypeTag[CC]], implicitly[Sizable[CC]])
      val ans = new Deployed[(Long, String), CC]{
        val secretly = gen
        var accesses: Int = 0
        val name = nm.value.toString
        def group = typeTagA.tpe.toString + " in " + nickname
        def apply(): Instance.FromArray[(Long, String), CC] = { accesses += 1; secretly }
      }
      registry += ans
      ans
    }
    val longMap = C({ a => val m = new collection.mutable.LongMap[String];     for (kv <- a) m += kv; m }, SUPER_ON_ZIP)
  }

  lazy val possible_a = Array(3L -> "wish")
  lazy val possible_x = Array(
    Array.empty[(Long, String)],
    possible_a,
    Array(1L -> "herring", 2L -> "cod", 3L -> "salmon")
  )
  lazy val possible_y = possible_x

  /** This is important!  This registers the collections that you actually want to have available! */
  val force = ImmKV :: MutKV :: MutLongV :: Nil
}

object InstantiatorsOfStrLong extends InstantiatorsOf[(String, Long)] with InstantiatorsOfKV[String, Long] {
  import Flag._

  protected implicit def orderingOfA = OrderingSource.orderingOfStringLong
  protected implicit def orderingOfK = OrderingSource.orderingOfString
  protected implicit def typeTagA = TypeTagSource.typeTagStringLong
  protected implicit def typeTagK = TypeTagSource.typeTagString
  protected implicit def typeTagV = TypeTagSource.typeTagLong
  protected def allFlags = Array[Flag]()

  protected implicit val sizeOfAnyRefMap_String_Long = 
    new Sizable[collection.mutable.AnyRefMap[String, Long]] { 
      def sizeof(m: collection.mutable.AnyRefMap[String, Long]) = m.size 
    }

  object MutKrefV extends Instance.PackagePath {
    // If we have other (String, _) types, move this out into a trait
    def nickname = "MutKrefV"
    def fullyQualified = "scala.collection.mutable"
    def C[CC: TypeTag: Sizable](ccf: Array[(String, Long)] => CC, flags: Flag*)(implicit nm: sourcecode.Name): Deployed[(String, Long), CC] = {
      val gen = kvInst.makeWith(ccf, (MAP +: flags): _*)(nm, implicitly[TypeTag[CC]], implicitly[Sizable[CC]])
      val ans = new Deployed[(String, Long), CC]{
        val secretly = gen
        var accesses: Int = 0
        val name = nm.value.toString
        def group = typeTagA.tpe.toString + " in " + nickname
        def apply(): Instance.FromArray[(String, Long), CC] = { accesses += 1; secretly }
      }
      registry += ans
      ans
    }
    val anyRefMap = C({ a => val m = new collection.mutable.AnyRefMap[String, Long];     for (kv <- a) m += kv; m })
  }

  lazy val possible_a = Array("wish" -> 3L)
  lazy val possible_x = Array(
    Array.empty[(String, Long)],
    possible_a,
    Array("herring" -> 1L, "cod" -> 2L, "salmon" -> 3L)
  )
  lazy val possible_y = possible_x

  /** This is important!  This registers the collections that you actually want to have available! */
  val force = ImmKV :: MutKV :: MutKrefV :: Nil
}
