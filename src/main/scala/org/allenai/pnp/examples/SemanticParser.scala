package org.allenai.pnp.examples

import scala.collection.JavaConverters._
import scala.collection.mutable.HashMap
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.{ Map => MutableMap }
import scala.collection.mutable.{ Set => MutableSet }
import scala.collection.mutable.MultiMap

import org.allenai.pnp.Pp
import org.allenai.pnp.Pp._
import org.allenai.pnp.PpUtil

import com.jayantkrish.jklol.ccg.lambda.Type
import com.jayantkrish.jklol.ccg.lambda.TypeDeclaration
import com.jayantkrish.jklol.ccg.lambda2.Expression2
import com.jayantkrish.jklol.ccg.lambda2.StaticAnalysis
import com.jayantkrish.jklol.ccg.lambda2.ExpressionSimplifier
import scala.collection.mutable.Queue
import com.google.common.base.Preconditions
import java.util.Arrays

case class ExpressionPart(val expr: Expression2,
    val holes: Array[Int], val holeIds: Array[Int]) {
  Preconditions.checkArgument(holes.length == holeIds.length)
  
  override def toString: String = {
    "ExpressionPart(" + expr + ", " + Arrays.toString(holes) + ", " + Arrays.toString(holeIds) 
  }
}

case class SemanticParserState(val parts: Map[Int, ExpressionPart],
    val unfilledHoleIds: List[(Int, Type, Scope)], val nextId: Int,
    val currentScope: Scope) {
  
  def decodeExpression(partId: Int): Expression2 = {
    val part = parts(partId)
    
    var expr = part.expr
    for (i <- 1 to part.holes.length) {
      val ind = part.holes.length - i
      var subexpr = decodeExpression(part.holeIds(ind))
      expr = expr.substitute(part.holes(ind), subexpr)
    }

    expr
  }
  
  def decodeExpression: Expression2 = {
    Preconditions.checkState(unfilledHoleIds.length == 0)
    decodeExpression(0)
  }
}

object SemanticParserState {
  def start(t: Type): SemanticParserState = {
    val scope = Scope(List.empty)    
    SemanticParserState(Map.empty, List((0, t, scope)), 1, scope) 
  }
}

sealed trait Template {
  val root: Type
  
  def apply(state: SemanticParserState): SemanticParserState
}

case class ApplicationTemplate(val root: Type, val elts: List[Type]) extends Template {
  val varNames: ListBuffer[Expression2] = ListBuffer.empty
  val holesBuffer: ListBuffer[Int] = ListBuffer.empty
  for (i <- 1 to elts.length) {
    varNames += Expression2.constant("$$" + i)
    holesBuffer += i
  }

  val expr = Expression2.nested(varNames.toList.asJava)
  val holes = holesBuffer.toArray
  val holeTypes = elts.toArray
  
  def apply(state: SemanticParserState): SemanticParserState = {
    val holeIds = ListBuffer.empty[Int]
    for (i <- state.nextId until (state.nextId + holes.length)) {
      holeIds += i
    }

    val filled = state.unfilledHoleIds.last

    val part = (filled._1, ExpressionPart(expr, holes.toArray, holeIds.toArray))

    val nextHoles = holeIds.zip(holeTypes).map(x => (x._1, x._2, state.currentScope))
    val next = state.unfilledHoleIds.dropRight(1) ++ nextHoles

    SemanticParserState(state.parts + part, next, state.nextId + holes.length, state.currentScope)
  }
}

case class ConstantTemplate(val root: Type, val expr: Expression2) extends Template {
  def apply(state: SemanticParserState): SemanticParserState = {
    val filled = state.unfilledHoleIds.last
    val part = (filled._1, ExpressionPart(expr, Array.empty[Int], Array.empty[Int]))
    val next = state.unfilledHoleIds.dropRight(1)
    SemanticParserState(state.parts + part, next, state.nextId + 1, state.currentScope)
  }
}

case class LambdaTemplate(val root: Type, val args: List[Type], val body: Type) extends Template {
  
  def apply(state: SemanticParserState): SemanticParserState = {
    val (nextScope, varNames) = state.currentScope.extend(args)
    
    val expr = Expression2.lambda(varNames.asJava, Expression2.constant("TEMP"))
    
    val hole = StaticAnalysis.getLambdaBodyIndex(expr, 0)
    val holeId = state.nextId
    
    val filled = state.unfilledHoleIds.last
    val part = (filled._1, ExpressionPart(expr, Array(hole), Array(holeId)))
    
    val next = state.unfilledHoleIds.dropRight(1) ++ Array((holeId, body, nextScope))
    SemanticParserState(state.parts + part, next, state.nextId + 1, nextScope)
  }
}

class Lexicon(
    val typeTemplateMap: MultiMap[Type, Template],
    val rootTypes: List[Type]
    ) {

  def getTemplates(t: Type): Vector[Template] = {
    typeTemplateMap.getOrElse(t, Set.empty).toVector
  }
}

case class ExpressionLabel(val expr: Expression2, val typeMap: Map[Integer, Type])

object ExpressionLabel {
  def fromExpression(expr: Expression2, typeDeclaration: TypeDeclaration): ExpressionLabel = {
    val canonicalExpr = ExpressionSimplifier.lambdaCalculus().apply(expr)
    
    val typeMap = StaticAnalysis.inferTypeMap(canonicalExpr, TypeDeclaration.TOP, typeDeclaration).asScala
    ExpressionLabel(canonicalExpr, typeMap.toMap)
  }
}

case class Scope(val vars: List[(Expression2, Type)]) {

  def getVariableExpressions(t: Type): List[Expression2] = {
    vars.filter(_._2.equals(t)).map(_._1)
  }
  
  def extend(types: List[Type]): (Scope, List[String]) = {
    var varNames = List[String]()
    var nextVars = vars
    for (t <- types) {
      val varName = "$" + nextVars.size
      varNames = varName :: varNames
      nextVars = (Expression2.constant(varName), t) :: nextVars
    }

    val nextScope = new Scope(nextVars)
    (nextScope, varNames)
  }
}

class SemanticParser(lexicon: Lexicon) {

  def generateExpression(): Pp[Expression2] = {
    for {
      rootType <- choose(lexicon.rootTypes)
      e <- generateExpression(SemanticParserState.start(rootType)) 
    } yield {
      e
    }
  }
  
  def generateExpression(rootType: Type): Pp[Expression2] = {
    generateExpression(SemanticParserState.start(rootType))
  }

  def generateExpression(state: SemanticParserState): Pp[Expression2] = {
    if (state.unfilledHoleIds.length == 0) {
      Pp.value(state.decodeExpression)
    } else {
      val (holeId, t, scope) = state.unfilledHoleIds.last
      val applicableTemplates = lexicon.getTemplates(t)
      // TODO: variables from lambda expressions
      for {
        template <- choose(applicableTemplates)
        _ <- score(0.9)
        nextState = template.apply(state)
        expr <- generateExpression(nextState)
      } yield {
        expr
      }
    }
  }
}

object SemanticParser {
  
  def generateLexicon(data: Seq[Expression2], typeDeclaration: TypeDeclaration): Lexicon = {
    val applicationTemplates = for {
      x <- data
      template <- SemanticParser.generateApplicationTemplates(x, typeDeclaration) 
    } yield {
      template
    }
  
    val lambdaTemplates = for {
      x <- data
      template <- SemanticParser.generateLambdaTemplates(x, typeDeclaration) 
    } yield {
      template
    }
  
    val constantTemplates = for {
      x <- data
      typeMap = StaticAnalysis.inferTypeMap(x, TypeDeclaration.TOP, typeDeclaration).asScala
      constant <- StaticAnalysis.getFreeVariables(x).asScala
      typeInd <- StaticAnalysis.getIndexesOfFreeVariable(x, constant)
      t = typeMap(typeInd)
    } yield {
      ConstantTemplate(t, Expression2.constant(constant))
    }
    
    val rootTypes = for {
      x <- data
      typeMap = StaticAnalysis.inferTypeMap(x, TypeDeclaration.TOP, typeDeclaration).asScala
    } yield {
      typeMap(0)
    }
  
    val allTemplates = (applicationTemplates ++ lambdaTemplates ++ constantTemplates)
    val templateMap = allTemplates.map(x => (x.root, x))

    new Lexicon(seqToMultimap(templateMap), rootTypes.toSet.toList)
  }

  def seqToMultimap[A, B](s: Seq[(A, B)]) = { 
    s.foldLeft(new HashMap[A, MutableSet[B]] with MultiMap[A, B]){ 
      (acc, pair) => acc.addBinding(pair._1, pair._2)
    }
  }

  def generateLambdaTemplates(
      e: Expression2,
      typeDeclaration: TypeDeclaration
    ): List[LambdaTemplate] = {
    val typeMap = StaticAnalysis.inferTypeMap(e, TypeDeclaration.TOP, typeDeclaration).asScala
    val builder = ListBuffer[LambdaTemplate]()

    for (scope <- StaticAnalysis.getScopes(e).getScopes.asScala) {
      if (scope.getStart != 0) {
        val i = scope.getStart - 1

        val root = typeMap(i)
        val argTypes = StaticAnalysis.getLambdaArgumentIndexes(e, i).map(typeMap(_)).toList
        val bodyType = typeMap(StaticAnalysis.getLambdaBodyIndex(e, i))
      
        builder += LambdaTemplate(root, argTypes, bodyType)
      }
    }

    builder.toList
  }
  
  def generateApplicationTemplates(
      e: Expression2,
      typeDeclaration: TypeDeclaration
    ): List[ApplicationTemplate] = {
    val typeMap = StaticAnalysis.inferTypeMap(e, TypeDeclaration.TOP, typeDeclaration)
    val builder = ListBuffer[ApplicationTemplate]()
    generateApplicationTemplates(e, 0, typeMap.asScala, builder)
    builder.toList
  }
  
  def generateApplicationTemplates(
      e: Expression2,
      index: Int,
      typeMap: MutableMap[Integer, Type],
      builder: ListBuffer[ApplicationTemplate]
    ): Unit = {
    if (StaticAnalysis.isLambda(e, index)) {
      generateApplicationTemplates(e, StaticAnalysis.getLambdaBodyIndex(e, index),
          typeMap, builder)
    } else {
      val subexpr = e.getSubexpression(index)
      if (!subexpr.isConstant) {
        val rootType = typeMap(index)
        val subtypes = e.getChildIndexes(index).map(x => typeMap(x)).toList
        builder += ApplicationTemplate(rootType, subtypes)
      
        for (childIndex <- e.getChildIndexes(index)) {
          generateApplicationTemplates(e, childIndex, typeMap, builder)
        }
      }
    }
  }
}