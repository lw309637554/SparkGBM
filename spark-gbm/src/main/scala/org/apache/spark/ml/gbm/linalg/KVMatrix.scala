package org.apache.spark.ml.gbm.linalg

import scala.collection.mutable
import scala.reflect.ClassTag
import scala.{specialized => spec}


/**
  * Compress a block of vectors in a compact fashion.
  * Note: all vectors are of the same length.
  *
  * @param indices concatenated indices of SparseVectors
  * @param values  concatenated indices of both DenseVectors and SparseVectors
  * @param steps   If non-empty, means length of vector-values (not vector-size).
  *                - Positive step indicate that the vector is a DenseVector,
  *                - Negative step indicate that the vector is a SparseVector (size of active values).
  *                If empty, means all vectors are dense.
  * @param flag    if positive, means vector-size;
  *                if negative, means number of empty-vector;
  *                if zero, means empty matrix.
  */
class KVMatrix[@spec(Byte, Short, Int) K, @spec(Byte, Short, Int) V](val indices: Array[K],
                                                                     val values: Array[V],
                                                                     val steps: Array[Int],
                                                                     val flag: Int) extends Serializable {
  if (flag > 0 && steps.isEmpty) {
    require(values.length % flag == 0)
  }


  def size: Int = {
    if (flag <= 0) {
      -flag

    } else {
      if (steps.nonEmpty) {
        steps.length
      } else {
        values.length / flag
      }
    }
  }

  def isEmpty: Boolean = size == 0

  def nonEmpty: Boolean = !isEmpty

  private def getStep(i: Int): Int = {
    if (steps.nonEmpty) {
      steps(i)
    } else {
      flag
    }
  }

  def iterator()
              (implicit ck: ClassTag[K], nek: NumericExt[K],
               cv: ClassTag[V], nev: NumericExt[V]): Iterator[KVVector[K, V]] = {

    if (flag <= 0) {
      Iterator.fill(-flag)(KVVector.empty[K, V])

    } else {

      val size_ = size

      new Iterator[KVVector[K, V]]() {
        private var i = 0
        private var indexIdx = 0
        private var valueIdx = 0

        private val emptyVec = KVVector.sparse[K, V](flag, nek.emptyArray, nev.emptyArray)

        override def hasNext: Boolean = i < size_

        override def next(): KVVector[K, V] = {
          val step = getStep(i)

          if (step > 0) {
            val ret = KVVector.dense[K, V](values.slice(valueIdx, valueIdx + step))

            valueIdx += step
            i += 1

            ret

          } else if (step < 0) {
            val ret = KVVector.sparse[K, V](flag, indices.slice(indexIdx, indexIdx - step),
              values.slice(valueIdx, valueIdx - step))

            indexIdx -= step
            valueIdx -= step
            i += 1

            ret

          } else {
            i += 1
            emptyVec
          }
        }
      }
    }
  }

}


object KVMatrix extends Serializable {

  def build[@spec(Byte, Short, Int) K, @spec(Byte, Short, Int) V](iterator: Iterator[KVVector[K, V]])
                                                                 (implicit ck: ClassTag[K],
                                                                  cv: ClassTag[V]): KVMatrix[K, V] = {
    val indexBuilder = mutable.ArrayBuilder.make[K]
    val valueBuilder = mutable.ArrayBuilder.make[V]
    val stepBuilder = mutable.ArrayBuilder.make[Int]

    var cnt = 0
    var vecSize = -1
    var allDense = true

    iterator.foreach { vec =>
      cnt += 1

      if (vecSize < 0) {
        vecSize = vec.size
      }
      require(vecSize == vec.size)

      vec match {
        case dv: DenseKVVector[K, V] =>
          valueBuilder ++= dv.values
          stepBuilder += dv.values.length

        case sv: SparseKVVector[K, V] =>
          allDense = false
          indexBuilder ++= sv.indices
          valueBuilder ++= sv.values
          stepBuilder += -sv.values.length
      }
    }


    if (vecSize < 0) {
      // empty input
      new KVMatrix[K, V](Array.empty[K], Array.empty[V], Array.emptyIntArray, 0)

    } else if (vecSize == 0) {
      // all input vectors are empty
      new KVMatrix[K, V](Array.empty[K], Array.empty[V], Array.emptyIntArray, -cnt)

    } else {

      if (allDense) {
        // all vec-value are of same length
        new KVMatrix[K, V](indexBuilder.result(), valueBuilder.result(), Array.emptyIntArray, vecSize)
      } else {
        new KVMatrix[K, V](indexBuilder.result(), valueBuilder.result(), stepBuilder.result(), vecSize)
      }
    }
  }

  def build[@spec(Byte, Short, Int) K, @spec(Byte, Short, Int) V](seq: Iterable[KVVector[K, V]])
                                                                 (implicit ck: ClassTag[K],
                                                                  cv: ClassTag[V]): KVMatrix[K, V] = {
    build[K, V](seq.iterator)
  }
}

