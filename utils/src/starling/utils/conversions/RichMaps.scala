package starling.utils.conversions
import starling.utils.ImplicitConversions._
import collection.SortedMap
import starling.utils.Pattern.Extractor
import collection.mutable.{Map => MMap}
import scalaz.Scalaz._
import scala.collection.MapProxy
import scala.collection.immutable.{Map, TreeMap}


trait RichMaps {
  type MultiMap[K, V] = Map[K, List[V]]
  type NestedMap[K1, K2, V] = Map[K1, Map[K2, V]]
  type NestedMap3[K1, K2, K3, V] = Map[K1, Map[K2, Map[K3, V]]]

  implicit def enrichMap[K, V](map: Map[K,V]) = new RichMap(map)
  implicit def enrichMultiMap[K, V](multi : MultiMap[K, V]) = new RichMultiMap[K, V](multi)
  implicit def enrichNestedMap[K1, K2, V](nested: NestedMap[K1, K2, V]) = new RichNestedMap(nested)
  implicit def enrichMutableMap[K, V](mutable: MMap[K, V]) = new RichMutableMap(mutable)

  def MultiMap[K, V](entries: (K, List[V])*): MultiMap[K, V] = entries.toMap
}

class RichMap[K,V](map : Map[K,V]) { thisMap =>
  def get(key: Option[K]) = key.map(map.get(_)).flatOpt
  def getOrThrow(k: K): V = getOrThrow(k, "key not found: %s, available: %s" % (k, map.keySet.mkString(", ")))
  def getOrThrow(key: K, msg: => String): V = map.getOrElse(key, throw new NoSuchElementException(msg))
  def either(key: K): Either[K, V] = map.get(key).either(key, identity)
  def getOrUpdate(k: K, f: (V) => V) = map.get(k).fold(v => map.updated(k, f(v)), map)
  def &(keys:Set[K]) : Map[K,V] = if (keys.isEmpty) map else map.filterKeys(key => keys.contains(key)).toList.toMap
  def mapValue(key: K, f: V => V): Map[K,V] = map.updated(key, f(map(key)))
  def mapKeys[C](f: K => C): Map[C, V] = map.map(kv => (f(kv._1), kv._2))
  def contraMapKeys[C](f: C => K) = new MapView(map, f)
  def castKeys[C >: K]() = map.asInstanceOf[Map[C, V]]
  def addSome(key: K, value: Option[V]): Map[K,V] = value.map(v => map + key → v).getOrElse(map)
  def addSome(keyValue: (K, Option[V])): Map[K,V] = addSome(keyValue._1, keyValue._2)
  def reverse: Map[V, K] = map.map(_.swap)
  def collectKeys[C](pf: PartialFunction[K, C]): Map[C, V] = map.collect(pf *** identity[V] _)
  def collectValues[W](pf: PartialFunction[V, W]): Map[K, W] = map.collect(identity[K] _ *** pf)
  def collectValuesO[W](f: V => Option[W]): Map[K, W] = map.mapValues(f).collectValues { case value if value.isDefined => value.get }
  def zipMap[W](other: Map[K, W]): Map[K, (V, W)] = {
    val (m, o) = (map.filterKeys(other.keySet), other.filterKeys(map.keySet))
    m.map { case (key, value) => (key, (value, o(key)))}.toMap
  }
  def sorted(implicit ordering: Ordering[K]): SortedMap[K, V] = TreeMap.empty[K, V](ordering) ++ map
  def sortKeysBy[S](f: K => S)(implicit ordering: Ordering[S]): SortedMap[K, V] = sorted(ordering.contraMap(f))
  def sortKeysWith(lt: (K, K) => Boolean): SortedMap[K, V] = sorted(Ordering fromLessThan lt)
  def filterKeysNot(f: K => Boolean): Map[K, V] = map.filterKeys(!f(_))
  def filterValues(f: V => Boolean): Map[K, V] = map.filter(p => f(p._2))
  def filterValuesNot(f: V => Boolean): Map[K, V] = map.filter(p => !f(p._2))
  def forallValues(p: V => Boolean): Boolean = map.forall(kv => p(kv._2))
  def toExtractor = Extractor.from[K](map.get)
  def valueExists(p: V => Boolean): Boolean = map.exists(kv => p(kv._2))
  def difference(other: Map[K, V]): Map[K, V] = map.filterKeys(key => map.get(key) != other.get(key))
  def mapValuesEagerly[C](f: V => C): Map[K, C] = map.mapValues(f).toList.toMap
  def mutable: MMap[K, V] = MMap.empty[K, V] ++ map
  def partitionKeys(p: K => Boolean): (Map[K, V], Map[K, V]) = map.partition(kv => p(kv._1))
  def ifDefined[B](f: (Map[K, V] => B)): Option[B] = map.isEmpty ? none[B] | some(f(map))
  def withDefault(f : K => V) = new MapProxy[K, V]{val self = thisMap.map; override def default(k : K) = f(k)}
  def innerJoin[W](other: Map[V, W]): Map[K, W] = map.collectValuesO(other.get)
  def combine(other: Map[K, V], combiner: (List[V]) => V): Map[K, V] = (map.toSeq ++ other.toSeq).toMultiMap.mapValues(combiner)
}

class RichMultiMap[K, V](map : Map[K, List[V]]) extends RichMap[K, List[V]](map) {
  lazy val multiSize = map.values.map(_.size).sum
  def multiMapValues[W](f: (V) => W): MultiMap[K, W] = map.mapValues(_.map(f))
  def contains(key : K, value : V) : Boolean = map.get(key).map(_.contains(value)).getOrElse(false)
  def contains(pair : (K, V)) : Boolean = contains(pair._1, pair._2)
  def allValues: List[V] = map.values.flatten.toList
  def union(k: K, v: List[V]): Map[K, List[V]] = map.getOrUpdate(k, old => (old ++ v).distinct)
  def union(kv: (K, List[V])): Map[K, List[V]] = union(kv._1, kv._2)
  def union(other: Map[K, List[V]]): Map[K, List[V]] = map.combine(other, _.reverse.flatten.distinct)
  def flatMultiMap[W](f: ((K, V)) => W): Iterable[W] = map.flatMap(kvs => kvs._2.map(v => f(kvs._1, v)))
  def reverseMulti: MultiMap[V, K] = flatMultiMap(_.swap).toMultiMap
}

class RichMutableMap[K, V](map: MMap[K, V]) {
  def collectValues[W](pf: PartialFunction[V, W]): MMap[K, W] = map.collect(identity[K] _ *** pf)
  def findOrUpdate(p: ((K, V)) => Boolean, newEntry: => (K, V)): (K, V) = map.find(p).getOrElse(newEntry.update(map.update(_)))
  def insertOrUpdate(k: K, initial: V, f: V => V): Option[V] = map.get(k).update { prior => map.put(k, prior.fold(f, initial)) }
  def update(kv: (K, V)) = map.update(kv._1, kv._2)
  def updateValue(k: K, vf: (V) => V) = map.update(k, vf(map(k)))
  def filterValues(f: V => Boolean): MMap[K, V] = map.filter(p => f(p._2))
  def remove(f : (K, V) => Boolean): MMap[K, V] = map.retain(f negate)
  def removeKeys(f: K => Boolean): MMap[K, V] = remove((k, V) => f(k))
  def removeValues(f: V => Boolean): MMap[K, V] = remove((k, v) => f(v))
  def retainKeys(f: K => Boolean): MMap[K, V] = map.retain((k, v) => f(k))
  def retainValues(f: V => Boolean): MMap[K, V] = map.retain((k, v) => f(v))
  def getOrThrow(key: K, msg: String) = map.getOrElse(key, throw new Exception(msg))
}

class RichNestedMap[K1, K2, V](nested: Map[K1, Map[K2, V]]) extends RichMap[K1, Map[K2, V]](nested) {
  lazy val nestedSize: Int = nested.values.map(_.size).sum

  def mapNested[T](f: (K1, K2, V) => T): Iterable[T] =
    nested.flatMap { case (k1, values) => values.map { case (k2, value) => f(k1, k2, value) } }
  def mapNestedValues[W](f: (V) => W): NestedMap[K1, K2, W] = mapNested { case (k1, k2, v) => (k1, (k2, f(v))) }.toNestedMap
  def mapOuter[K](f: (K1, K2, V) => K): NestedMap[K, K2, V] = mapNested { case (k1, k2, v) => (f(k1, k2, v), (k2, v)) }.toNestedMap
  def mapInner[K](f: (K1, K2, V) => K): NestedMap[K1, K, V] = mapNested { case (k1, k2, v) => (k1, (f(k1, k2, v), v)) }.toNestedMap
  def mapInnerValues[W](f: V => W): NestedMap[K1, K2, W] = nested.mapValues(_.mapValues(f))
  def extractKeys: MultiMap[K1, K2] = mapNested { case (k1, k2, _) => (k1, k2) }.toMultiMap
  def flipNesting: NestedMap[K2, K1, V] = mapNested { case (k1, k2, v) => (k2, (k1, v)) }.toNestedMap
  def allValues: Iterable[V] = nested.values.flatMap(_.values)
  def pairKeys: Map[(K1, K2), V] = mapNested { case (k1, k2, v) => ((k1, k2), v) }.toMap
}

class MapView[K, V, C](map: Map[K, V], keyProjection: C => K) {
  def apply(key: C): V = map.apply(keyProjection(key))
  def get(key: C): Option[V] = map.get(keyProjection(key))
  def contains(key: C): Boolean = map.contains(keyProjection(key))
}

case class RichMapWithErrors[K : Manifest, V : Manifest](map : Map[K, V]) {
  val defaultMissingKeyExceptionMessage = "Missing key '%s' of type '%s', for values of type '%s'"
  def withException(s : String = defaultMissingKeyExceptionMessage) : Map[K, V] =
    map.withDefault(k => throw new java.util.NoSuchElementException(s.format(if (k == null) "Null key" else k.toString, manifest[K].erasure.getName, manifest[V].erasure.getName)))
}
object RichMapWithErrors {
  implicit def toRichMap[K : Manifest, V : Manifest](map : Map[K, V]) = RichMapWithErrors(map)
}
