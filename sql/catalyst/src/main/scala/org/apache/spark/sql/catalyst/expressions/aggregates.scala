/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.sql.catalyst.types._
import org.apache.spark.sql.catalyst.trees
import org.apache.spark.sql.catalyst.errors.TreeNodeException

abstract class AggregateExpression extends Expression {
  self: Product =>

  /**
   * Creates a new instance that can be used to compute this aggregate expression for a group
   * of input rows/
   */
  def newInstance(): AggregateFunction

  /**
   * [[AggregateExpression.eval]] should never be invoked because [[AggregateExpression]]'s are
   * replaced with a physical aggregate operator at runtime.
   */
  override def eval(input: Row = null): EvaluatedType =
    throw new TreeNodeException(this, s"No function to evaluate expression. type: ${this.nodeName}")
}

/**
 * Represents an aggregation that has been rewritten to be performed in two steps.
 *
 * @param finalEvaluation an aggregate expression that evaluates to same final result as the
 *                        original aggregation.
 * @param partialEvaluations A sequence of [[NamedExpression]]s that can be computed on partial
 *                           data sets and are required to compute the `finalEvaluation`.
 */
case class SplitEvaluation(
    finalEvaluation: Expression,
    partialEvaluations: Seq[NamedExpression])

/**
 * An [[AggregateExpression]] that can be partially computed without seeing all relevant tuples.
 * These partial evaluations can then be combined to compute the actual answer.
 */
abstract class PartialAggregate extends AggregateExpression {
  self: Product =>

  /**
   * Returns a [[SplitEvaluation]] that computes this aggregation using partial aggregation.
   */
  def asPartial: SplitEvaluation
}

/**
 * A specific implementation of an aggregate function. Used to wrap a generic
 * [[AggregateExpression]] with an algorithm that will be used to compute one specific result.
 */
abstract class AggregateFunction
  extends AggregateExpression with Serializable with trees.LeafNode[Expression] {
  self: Product =>

  override type EvaluatedType = Any

  /** Base should return the generic aggregate expression that this function is computing */
  val base: AggregateExpression
  override def references = base.references
  override def nullable = base.nullable
  override def dataType = base.dataType

  def update(input: Row): Unit
  def merge(other: AggregateFunction): Unit
  override def eval(input: Row): Any

  // Do we really need this?
  override def newInstance() = makeCopy(productIterator.map { case a: AnyRef => a }.toArray)
}

case class Count(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {
  override def references = child.references
  override def nullable = false
  override def dataType = IntegerType
  override def toString = s"COUNT($child)"

  override def asPartial: SplitEvaluation = {
    val partialCount = Alias(Count(child), "PartialCount")()
    SplitEvaluation(Sum(partialCount.toAttribute), partialCount :: Nil)
  }

  override def newInstance()= new CountFunction(child, this)
}

case class CountDistinct(expressions: Seq[Expression]) extends AggregateExpression {
  override def children = expressions
  override def references = expressions.flatMap(_.references).toSet
  override def nullable = false
  override def dataType = IntegerType
  override def toString = s"COUNT(DISTINCT ${expressions.mkString(",")}})"
  override def newInstance()= new CountDistinctFunction(expressions, this)
}

case class Average(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {
  override def references = child.references
  override def nullable = false
  override def dataType = DoubleType
  override def toString = s"AVG($child)"

  override def asPartial: SplitEvaluation = {
    val partialSum = Alias(Sum(child), "PartialSum")()
    val partialCount = Alias(Count(child), "PartialCount")()
    val castedSum = Cast(Sum(partialSum.toAttribute), dataType)
    val castedCount = Cast(Sum(partialCount.toAttribute), dataType)

    SplitEvaluation(
      Divide(castedSum, castedCount),
      partialCount :: partialSum :: Nil)
  }

  override def newInstance()= new AverageFunction(child, this)
}

case class Sum(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {
  override def references = child.references
  override def nullable = false
  override def dataType = child.dataType
  override def toString = s"SUM($child)"

  override def asPartial: SplitEvaluation = {
    val partialSum = Alias(Sum(child), "PartialSum")()
    SplitEvaluation(
      Sum(partialSum.toAttribute),
      partialSum :: Nil)
  }

  override def newInstance()= new SumFunction(child, this)
}

case class SumDistinct(child: Expression)
  extends AggregateExpression with trees.UnaryNode[Expression] {

  override def references = child.references
  override def nullable = false
  override def dataType = child.dataType
  override def toString = s"SUM(DISTINCT $child)"

  override def newInstance()= new SumDistinctFunction(child, this)
}

case class First(child: Expression) extends PartialAggregate with trees.UnaryNode[Expression] {
  override def references = child.references
  override def nullable = child.nullable
  override def dataType = child.dataType
  override def toString = s"FIRST($child)"

  override def asPartial: SplitEvaluation = {
    val partialFirst = Alias(First(child), "PartialFirst")()
    SplitEvaluation(
      First(partialFirst.toAttribute),
      partialFirst :: Nil)
  }
  override def newInstance()= new FirstFunction(child, this)
}

case class AverageFunction(expr: Expression, base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  private var count: Long = _
  private val sum = MutableLiteral(Cast(Literal(0), expr.dataType).eval(EmptyRow))
  private val sumAsDouble = Cast(sum, DoubleType)

  private val addFunction = Add(sum, expr)

  override def eval(input: Row): Any =
    sumAsDouble.eval(EmptyRow).asInstanceOf[Double] / count.toDouble

  override def update(input: Row): Unit = {
    count += 1
    sum.update(addFunction, input)
  }

  override def merge(other: AggregateFunction): Unit = {
    other match {
      case avg: AverageFunction => {
        count += avg.count
        sum.update(Add(sum, avg.sum), EmptyRow)
      }
      case _ => throw new TreeNodeException(this, s"Types do not match ${this.dataType} != ${other.dataType}")
    }
  }
}

case class CountFunction(expr: Expression, base: AggregateExpression) extends AggregateFunction {
  def this() = this(null, null) // Required for serialization.

  var count: Int = _

  override def update(input: Row): Unit = {
    val evaluatedExpr = expr.map(_.eval(input))
    if (evaluatedExpr.map(_ != null).reduceLeft(_ || _)) {
      count += 1
    }
  }

  override def merge(other: AggregateFunction): Unit = {
    other match {
      case c: CountFunction => {
        count += c.count
      }
      case _ => throw new TreeNodeException(this, s"Types do not match ${this.dataType} != ${other.dataType}")
    }
  }

  override def eval(input: Row): Any = count
}

case class SumFunction(expr: Expression, base: AggregateExpression) extends AggregateFunction {
  def this() = this(null, null) // Required for serialization.

  private val sum = MutableLiteral(Cast(Literal(0), expr.dataType).eval(null))

  private val addFunction = Add(sum, expr)

  override def update(input: Row): Unit = {
    sum.update(addFunction, input)
  }

  override def merge(other: AggregateFunction): Unit = {
    other match {
      case s: SumFunction => {
        sum.update(Add(sum, s.sum), EmptyRow)
      }
      case _ => throw new TreeNodeException(this, s"Types do not match ${this.dataType} != ${other.dataType}")
    }
  }

  override def eval(input: Row): Any = sum.eval(null)
}

case class SumDistinctFunction(expr: Expression, base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  private val seen = new scala.collection.mutable.HashSet[Any]()

  override def update(input: Row): Unit = {
    val evaluatedExpr = expr.eval(input)
    if (evaluatedExpr != null) {
      seen += evaluatedExpr
    }
  }

  override def merge(other: AggregateFunction): Unit = {
    other match {
      case sd: SumDistinctFunction => {
        // TODO(lamuguo): Change to HashSet union scala rebase to support it. Related change:
        // https://github.com/scala/scala/pull/3322
        for (item <- sd.seen) {
          seen += item
        }
      }
      case _ => throw new TreeNodeException(this, s"Types do not match ${this.dataType} != ${other.dataType}")
    }
  }

  override def eval(input: Row): Any =
    seen.reduceLeft(base.dataType.asInstanceOf[NumericType].numeric.asInstanceOf[Numeric[Any]].plus)
}

case class CountDistinctFunction(expr: Seq[Expression], base: AggregateExpression)
  extends AggregateFunction {

  def this() = this(null, null) // Required for serialization.

  val seen = new scala.collection.mutable.HashSet[Any]()

  override def update(input: Row): Unit = {
    val evaluatedExpr = expr.map(_.eval(input))
    if (evaluatedExpr.map(_ != null).reduceLeft(_ && _)) {
      seen += evaluatedExpr
    }
  }

  override def merge(other: AggregateFunction): Unit = {
    other match {
      case cd: CountDistinctFunction => {
        for (item <- cd.seen) {
          seen += item
        }
      }
      case _ => throw new TreeNodeException(this, s"Types do not match ${this.dataType} != ${other.dataType}")
    }
  }

  override def eval(input: Row): Any = seen.size
}

case class FirstFunction(expr: Expression, base: AggregateExpression) extends AggregateFunction {
  def this() = this(null, null) // Required for serialization.

  var result: Any = null

  override def update(input: Row): Unit = {
    if (result == null) {
      result = expr.eval(input)
    }
  }

  override def merge(other: AggregateFunction): Unit = {
    other match {
      case second: FirstFunction => {
        if (result == null) {
          result = second.result
        }
      }
      case _ => throw new TreeNodeException(this, s"Types do not match ${this.dataType} != ${other.dataType}")
    }
  }

  override def eval(input: Row): Any = result
}
