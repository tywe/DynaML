package org.kuleuven.esat.svm

import breeze.linalg.DenseVector
import breeze.numerics.sqrt
import org.apache.spark.SparkContext
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.kuleuven.esat.evaluation.Metrics
import org.kuleuven.esat.graphicalModels.GaussianLinearModel
import org.kuleuven.esat.optimization._
import org.apache.spark.mllib.linalg.Vector

/**
 * Implementation of the Least Squares SVM
 * using Apache Spark RDDs
 */
class LSSVMSparkModel(data: RDD[LabeledPoint], task: String)
  extends KernelSparkModel(data, task) with Serializable {

  override protected val optimizer = LSSVMSparkModel.getOptimizer(task)

  override protected var params: DenseVector[Double] = DenseVector.ones(featuredims+1)

  def dimensions = featuredims

  /**
   * Predict the value of the
   * target variable given a
   * point.
   *
   **/
  override def predict(point: DenseVector[Double]): Double =
    LSSVMSparkModel.predictBDV(params)(task)(point)

  /**
   * Learn the parameters
   * of the model which
   * are in a node of the
   * graph.
   *
   **/
  override def learn(): Unit = {
    val featureMapb = g.context.broadcast(featureMap)
    val meanb = g.context.broadcast(DenseVector(colStats.mean.toArray))
    val varianceb = g.context.broadcast(DenseVector(colStats.variance.toArray))
    val trainingData = this.g.map(point => {
      val vec = DenseVector(point._2.features.toArray)
      val ans = vec - meanb.value
      ans :/= sqrt(varianceb.value)
      new LabeledPoint(
        point._2.label,
        Vectors.dense(DenseVector.vertcat(
          featureMapb.value(ans),
          DenseVector(1.0))
          .toArray)
      )
    })
    params = this.optimizer.optimize(nPoints, trainingData, params)

  }

  override def clearParameters: Unit = {
    params = DenseVector.ones[Double](featuredims+1)
  }

  override def setRegParam(l: Double): this.type = {
    this.optimizer.setRegParam(l)
    this
  }

  override def getRegParam = this.optimizer.getRegParam
  
  override def evaluate(config: Map[String, String]) = {
    val sc = g.context
    val (file, delim, head, _) = GaussianLinearModel.readConfig(config)
    val csv = sc.textFile(file).map(line => line split delim)
      .map(_.map(_.toDouble)).map(vector => LabeledPoint(vector(vector.length-1), 
      Vectors.dense(vector.slice(0, vector.length-1))))

    val test_data = head match {
      case true =>
        csv.mapPartitionsWithIndex { (idx, iter) => if (idx == 0) iter.drop(1) else iter }
      case false =>
        csv
    }

    val mapPoint = (p: Vector) => Vectors.dense(featureMap(DenseVector(p.toArray)).toArray)
    val predict = LSSVMSparkModel.scoreSparkVector(params) _
    val mapPointb = sc.broadcast(mapPoint)
    val predictb = sc.broadcast(predict)
    val meanb = g.context.broadcast(DenseVector(colStats.mean.toArray))
    val varianceb = g.context.broadcast(DenseVector(colStats.variance.toArray))

    val results = test_data.map(point => {
      val vec = DenseVector(point.features.toArray)
      val ans = vec - meanb.value
      ans :/= sqrt(varianceb.value)
      (predictb.value(mapPointb.value(Vectors.dense(ans.toArray))), point.label)
    }).collect()

    Metrics(task)(results.toList, results.length)
  }

  def GetStatistics(): Unit = {
    println("Feature Statistics: \n")
    println("Mean: "+this.colStats.mean)
    println("Variance: \n"+this.colStats.variance)
  }

  def unpersist: Unit = {
    this.processed_g.unpersist()
    this.g.context.stop()
  }

  override def evaluateFold(params: DenseVector[Double])
                           (test_data_set: RDD[LabeledPoint])
                           (task: String): Metrics[Double] = {
    val sc = test_data_set.context
    var index: Int = 1

    val paramsb = sc.broadcast(params)
    val scoresAndLabels = test_data_set.map((e) => {
      index += 1
      (paramsb.value dot DenseVector(e.features.toArray), e.label)
    }).collect()
    Metrics(task)(scoresAndLabels.toList, index)
  }

}

object LSSVMSparkModel {

  def scaleAttributes(mean: Vector,
                      variance: Vector)(x: Vector)
  : Vector = {
    val ans = DenseVector(x.toArray) - DenseVector(mean.toArray)
    ans :/= sqrt(DenseVector(variance.toArray))
    Vectors.dense(ans.toArray)
  }

  def apply(implicit config: Map[String, String], sc: SparkContext): LSSVMSparkModel = {
    val (file, delim, head, task) = GaussianLinearModel.readConfig(config)
    val minPartitions = if(config.isDefinedAt("parallelism")) config("parallelism").toInt
    else 2

    val csv = sc.textFile(file, minPartitions).map(line => line split delim)
      .map(_.map(_.toDouble)).map(vector => {
      val label = vector(vector.length-1)
      vector(vector.length-1) = 1.0
      LabeledPoint(label, Vectors.dense(vector.slice(0, vector.length - 1)))
    })

    val data = head match {
      case true =>
        csv.mapPartitionsWithIndex { (idx, iter) => if (idx == 0) iter.drop(1) else iter }
      case false =>
        csv
    }

    new LSSVMSparkModel(data, task)
  }

  /**
   * Returns an indexed [[RDD]] from a non indexed [[RDD]] of [[T]]
   *
   * @param data : An [[RDD]] of [[T]]
   *
   * @return An (Int, T) Key-Value RDD indexed
   *         from 0 to data.count() - 1
   * */
  def indexedRDD[T](data: RDD[T]): RDD[(Long, T)] =
    data.zipWithIndex().map((p) => (p._2, p._1))

  def predict(params: DenseVector[Double])
             (_task: String)
             (point: LabeledPoint): (Double, Double) = {
    val margin: Double = predictSparkVector(params)(point.features)(_task)
    val loss = point.label - margin
    (margin, math.pow(loss, 2))
  }

  def scoreSparkVector(params: DenseVector[Double])
                      (point: Vector): Double = {
    params dot DenseVector.vertcat(
      DenseVector(point.toArray),
      DenseVector(1.0))
  }

  def predictBDV(params: DenseVector[Double])
                (_task: String)
                (point: DenseVector[Double]): Double = {
    val margin: Double = params dot DenseVector.vertcat(
      point,
      DenseVector(1.0))
    _task match {
      case "classification" => math.tanh(margin)
      case "regression" => margin

    }
  }

  def predictSparkVector(params: DenseVector[Double])
                (point: Vector)
                (implicit _task: String): Double =
  predictBDV(params)(_task)(DenseVector(point.toArray))

  /**
   * Factory method to create the appropriate
   * optimization object required for the Gaussian
   * model
   * */
  def getOptimizer(task: String): ConjugateGradientSpark = new ConjugateGradientSpark /*task match {
    case "classification" => new GradientDescentSpark(
      new LeastSquaresSVMGradient(),
      new SquaredL2Updater())

    case "regression" => new GradientDescentSpark(
      new LeastSquaresGradient(),
      new SquaredL2Updater())
  }*/

}