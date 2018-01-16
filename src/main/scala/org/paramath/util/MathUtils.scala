package org.paramath.util

import breeze.linalg.{DenseMatrix => BDM, DenseVector => BDV}

import org.apache.spark.SparkContext
import org.apache.spark.mllib.linalg.{DenseVector, Matrices, Matrix}
import org.apache.spark.mllib.linalg.distributed.{CoordinateMatrix, IndexedRow, IndexedRowMatrix, MatrixEntry}
import org.apache.spark.rdd.RDD

import scala.io.Source
import scala.util.Random

object MathUtils {

  def printTime(tick: Long, tock: Long, id: String) {
    val diff = tock-tick
    println(s"Code section $id took $diff milliseconds to run")
  }

  /**
    * Convert a coordinate matrix to a breeze matrix
    * @param data The coordinate matrix
    * @return The breeze matrix
    */
  def coordToBreeze(data: CoordinateMatrix, r: Int = -1, c: Int = -1): BDM[Double] = {
    val rows: Int = if (r == -1) data.numRows().toInt else r
    val cols: Int = if (c == -1) data.numCols().toInt else c
    var m: BDM[Double] = BDM.zeros(rows, cols)
    data.entries.collect().foreach(mel => {
      m(mel.i.toInt, mel.j.toInt) = mel.value
    })
    m
  }

  /**
    * Shifts the entries of a matrix left/right and up/down
    * @param A RDD of MatrixEntry
    * @param rowShift Amount of shift for rows (+/-)
    * @param colShift Amount of shift for columns (+/-)
    * @return RDD of shifted entries.
    */
  def shiftEntries(A: RDD[MatrixEntry], rowShift: Long, colShift: Long ): RDD[MatrixEntry] = {
    A.map({ case MatrixEntry(i, j, k) => MatrixEntry(i+rowShift, j+colShift, k ) })
  }

  /**
    * Take a percent of the columns of a matrix.
    * @param A matrix to take column samples of
    * @param percent percent of columns to take
    * @return RDD[MatrixEntry] with columns shifted to create a normal matrix
    */
  def sampleRows(A: CoordinateMatrix, percent: Double): RDD[MatrixEntry] = {
    val nrows = A.numRows()
    val pickNum = Math.floor(nrows*percent)
    if (pickNum < 1) {
      throw new IllegalArgumentException(s"Sample $percent is too low for matrix with $nrows columns")
    }

    val cols: Array[Int] = uniqueRandVals(0, nrows.toInt-1, pickNum.toInt) // Get the column indexes
    var colSet: Set[Int] = cols.toSet
    scala.util.Sorting.quickSort(cols) // Sort the picked column indexes

    // Now we need to pick out the columns.
    // First, transpose to rows
    // Filter out the rows we don't want
    // Set corresponding row index to their index in the array
    // Convert back to row matrix and transpose

    var filteredrows = A.toIndexedRowMatrix().rows
      .filter({ case IndexedRow(i, v) => colSet.contains(i.toInt) })
      .map({case IndexedRow(i, v) => IndexedRow(binSearch(cols, i.toInt).toLong, v)})

    new IndexedRowMatrix(filteredrows).toCoordinateMatrix().entries // Convert back to Coordinate matrix.
  }

  /**
    * Basic binary search - O(logn)
    * @param a The array
    * @param num Number to search for
    * @return Index of the number in the array
    */
  def binSearch(a: Array[Int], num: Int): Int = {
    var lo: Int = 0
    var hi: Int = a.length-1
    var mid: Int = (hi+lo) >>> 1
    while (lo <= hi) {
      mid = (hi+lo) >>> 1
      if (a(mid) == num) {
        return mid
      } else if (num < a(mid)){
        hi = mid - 1
      } else {
        lo = mid + 1
      }
    }
    -1
  }

  /**
    * Generates a sequence of unique random numbers in the range [min, max]
    * @param min The lowest number in the range
    * @param max The largest number in the range
    * @param n The number of samples to pull from the range.
    */
  def uniqueRandVals(min: Int, max: Int, n: Int): Array[Int] = {
    val rang = min to max
    val arr: Array[Int] = rang.toArray[Int]
    for (i <- (max-min) to 1 by -1) {
      var j: Int = Math.abs(Random.nextInt()) % i
      val tmp = arr(j)
      arr(j) = arr(i)
      arr(i) = tmp
    }

    arr.slice(0, n)
  }

  /**
    * Read the libSVM data file
    * @param fileLoc
    * @return (data, labels)
    */
  def readSVMData(fileLoc: String): (Seq[MatrixEntry], Seq[MatrixEntry]) = {
    // manually read data and create separate matrices for label and features
    var i = 0
    val filename = fileLoc
    var thisSeq = Seq[MatrixEntry]()
    var labelSeq = Seq[MatrixEntry]()

    for (line <- Source.fromFile(filename).getLines) {
      val items = line.split(' ')
      val label = items.head.toDouble
      val thisLabelEntry = MatrixEntry(i, 0, label)
      labelSeq = labelSeq :+ thisLabelEntry

      for (para <- items.tail) {
        val indexAndValue = para.split(':')
        val index = indexAndValue(0).toInt - 1 // Convert 1-based indices to 0-based.
        val value = indexAndValue(1).toDouble
        val thisEntry = MatrixEntry(i, index, value)
        thisSeq = thisSeq :+ thisEntry
      }
      i = i + 1
    }
    (thisSeq, labelSeq)
  }


  /**
    * Convert a breeze matrix to a coordinate matrix
    * @param A The breeze Matrix
    * @param sc Spark Context (used to parallelize)
    * @return A new CoordinateMatrix
    */
  def breezeMatrixToCoord(A: BDM[Double], sc: SparkContext): CoordinateMatrix = {
    var x: Matrix = Matrices.dense(A.rows, A.cols, A.data)
    val cols = x.toArray.grouped(x.numRows)
    var rows: Seq[Seq[Double]] = cols.toSeq.transpose
    var irows = rows.zipWithIndex.map(({ case (r, i) => (i, new DenseVector(r.toArray)) }))
    val vecs = irows.map({ case (i, vec) => new IndexedRow(i, vec) })
    val rddtemp: RDD[IndexedRow] = sc.parallelize(vecs, 2)
    var a = new IndexedRowMatrix(rddtemp)
    a.toCoordinateMatrix()
  }

  /**
    * Turns a breeze vector into a distributed coordinate matrix
    * @param A The vector to convert
    * @param sc The spark context
    * @return The coordinate matrix representing the vector
    */
  def breezeVectorToCoord(A: BDV[Double], sc: SparkContext): CoordinateMatrix = {
    var x: Matrix = Matrices.dense(A.length, 1, A.data)
    val cols = x.toArray.grouped(x.numRows)
    var rows: Seq[Seq[Double]] = cols.toSeq.transpose
    var irows = rows.zipWithIndex.map(({ case (r, i) => (i, new DenseVector(r.toArray)) }))
    val vecs = irows.map({ case (i, vec) => new IndexedRow(i, vec) })
    val rddtemp: RDD[IndexedRow] = sc.parallelize(vecs, 2)
    var a = new IndexedRowMatrix(rddtemp)
    a.toCoordinateMatrix()
  }


  /**
    * Multiply two Matrix Entry RDDs together.
    * @param rdd1
    * @param rdd2
    * @return The multiplication of the 2 RDD's
    */
  def RDDMult(rdd1: RDD[MatrixEntry], rdd2: RDD[MatrixEntry]): RDD[MatrixEntry] = {
    var a: RDD[(Long, (Long, Double))] = rdd1.map({ case MatrixEntry(i, j, k) => (j, (i, k)) })
    var b: RDD[(Long, (Long, Double))] = rdd2.map({ case MatrixEntry(i, j, k) => (i, (j, k)) })
    a.join(b)
      .map({ case (_, ((i, v), (k, w))) => ((i, k), (v * w)) })
      .reduceByKey(_ + _)
      .map({ case ((i, k), sum) => MatrixEntry(i, k, sum) })
  }

  def RDDAdd(rdd1: RDD[MatrixEntry], rdd2: RDD[MatrixEntry], sub: Boolean = false): RDD[MatrixEntry] = {
    var a: RDD[((Long, Long), Double)] = rdd1.map({ case MatrixEntry(i, j, k) => ((i, j), k) })
    var b: RDD[((Long, Long), Double)] = rdd2.map({ case MatrixEntry(i, j, k) => ((i, j), k) })

    if (sub){
      a.union(b).reduceByKey(_ - _).map({ case ((i, k), sum) => MatrixEntry(i, k, sum)})
    } else {
      a.union(b).reduceByKey(_ + _).map({ case ((i, k), sum) => MatrixEntry(i, k, sum)})
    }
  }

  /**
    * Calculate the piecewise S_lambda function
    * @param weights The initial weights
    * @param lambda The lambda value
    * @return The newly calculated S vector (as a CoordinateMatrix)
    */
  def Svec(weights: RDD[MatrixEntry], lambda: Double): CoordinateMatrix = {

    var wvec: RDD[MatrixEntry] = weights.map( w => {
      if (w.value > lambda) {
        MatrixEntry(w.i, w.j, w.value - lambda)
      } else if (w.value < -1*lambda) {
        MatrixEntry(w.i, w.j, 0)
      } else {
        MatrixEntry(w.i, w.j, w.value + lambda)
      }
    })
    new CoordinateMatrix(wvec)
  }

  /**
    * Calculates the Soft-Thresholding Operator
    * @param weights
    * @param lambda
    * @return
    */
  def Svec(weights: BDM[Double], lambda: Double): BDM[Double] = {

    weights.map(f => {
      if (f > lambda) {
        f - lambda
      } else if (f < -1*lambda) {
        f + lambda
      } else {
        0
      }
    })
  }

}