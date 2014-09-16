/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_1.planner.logical.steps

import org.neo4j.graphdb.Direction
import org.neo4j.cypher.internal.commons.CypherFunSuite
import org.neo4j.cypher.internal.compiler.v2_1.ast._
import org.neo4j.cypher.internal.compiler.v2_1.planner._
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.plans._
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.{LogicalPlanningContext, Cardinality, PlanTransformer}
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.steps.QueryPlanProducer._
import org.mockito.Mockito._
import org.mockito.Matchers._

class SelectPatternPredicatesTest extends CypherFunSuite with LogicalPlanningTestSupport {
  val dir = Direction.OUTGOING
  val types = Seq.empty[RelTypeName]
  val relName = "  UNNAMED1"
  val nodeName = "  UNNAMED2"
  val patternRel = PatternRelationship(relName, ("a", nodeName), dir, types, SimplePatternLength)

  // MATCH (a) WHERE (a)-->()
  val patternExp = PatternExpression(RelationshipsPattern(RelationshipChain(
    NodePattern(Some(ident("a")), Seq(), None, naked = false)_,
    RelationshipPattern(Some(ident(relName)), optional = false, types, None, None, dir) _,
    NodePattern(Some(ident(nodeName)), Seq(), None, naked = false)_
  )_)_)

  val factory = newMockedMetricsFactory
  when(factory.newCardinalityEstimator(any(), any(), any())).thenReturn((plan: LogicalPlan) => plan match {
    case _ => Cardinality(1000)
  })

  val passThrough = new PlanTransformer[QueryGraph] {
    def apply(input: QueryPlan, queryGraph: QueryGraph)(implicit context: LogicalPlanningContext, subQueriesLookupTable: Map[PatternExpression, QueryGraph]): QueryPlan = input
  }

  test("should introduce semi apply for unsolved exclusive pattern predicate") {
    // Given
    val predicate = Predicate(Set(IdName("a")), patternExp)
    val selections = Selections(Set(predicate))
    val patternQG = QueryGraph(
      patternRelationships = Set(patternRel),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName(nodeName))
    )

    val qg = QueryGraph(
      patternNodes = Set("a"),
      selections = selections
    )

    implicit val subQueryLookupTable = Map(patternExp -> patternQG)

    implicit val context = newMockedLogicalPlanningContext(
      planContext = newMockedPlanContext,
      metrics = factory.newMetrics(hardcodedStatistics, newMockedSemanticTable)
    )

    val aPlan = newMockedQueryPlan("a")
    val inner = planExpand(planArgumentRow(Set(IdName("a"))), IdName("a"), dir, types, IdName(nodeName), IdName(relName), SimplePatternLength, patternRel)

    // When
    val result = selectPatternPredicates(passThrough)(aPlan, qg)

    // Then
    result should equal(planSemiApply(aPlan, inner, patternExp))
  }

  test("should introduce anti semi apply for unsolved exclusive negated pattern predicate") {
    val notExpr = Not(patternExp)_
    // Given
    val predicate = Predicate(Set(IdName("a")), notExpr)
    val selections = Selections(Set(predicate))
    val patternQG = QueryGraph(
      patternRelationships = Set(patternRel),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName(nodeName))
    )

    val qg = QueryGraph(
      patternNodes = Set("a"),
      selections = selections
    )

    implicit val subQueryLookupTable = Map(patternExp -> patternQG)

    implicit val context = newMockedLogicalPlanningContext(
      planContext = newMockedPlanContext,
      metrics = factory.newMetrics(hardcodedStatistics, newMockedSemanticTable)
    )

    val aPlan = newMockedQueryPlan("a")
    val inner = planExpand(planArgumentRow(Set(IdName("a"))), IdName("a"), dir, types, IdName(nodeName), IdName(relName), SimplePatternLength, patternRel)

    // When
    val result = selectPatternPredicates(passThrough)(aPlan, qg)

    // Then
    result should equal(planAntiSemiApply(aPlan, inner, patternExp, notExpr))
  }

  test("should not introduce semi apply for unsolved exclusive pattern predicate when nodes not applicable") {
    // Given
    val predicate = Predicate(Set(IdName("a")), patternExp)
    val selections = Selections(Set(predicate))
    val patternQG = QueryGraph(
      patternRelationships = Set(patternRel),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName(nodeName))
    )


    val qg = QueryGraph(
      patternNodes = Set("b"),
      selections = selections
    )

    implicit val subQueryLookupTable = Map(patternExp -> patternQG)

    implicit val context = newMockedLogicalPlanningContext(
      planContext = newMockedPlanContext,
      metrics = factory.newMetrics(hardcodedStatistics, newMockedSemanticTable)
    )

    val bPlan = newMockedQueryPlan("b")
    // When
    val result = selectPatternPredicates(passThrough)(bPlan, qg)

    // Then
    result should equal(bPlan)
  }

  test("should introduce select or semi apply for unsolved pattern predicates in disjunction with expressions") {
    // Given
    val equals = Equals(
      Property(ident("a"), PropertyKeyName("prop")_)_,
      StringLiteral("42")_
    )_
    val orsExp: Ors = Ors(Set(patternExp, equals))_
    val orPredicate = Predicate(Set(IdName("a")), orsExp)
    val selections = Selections(Set(orPredicate))
    val patternQG = QueryGraph(
      patternRelationships = Set(patternRel),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName(nodeName))
    )

    val qg = QueryGraph(
      patternNodes = Set("a"),
      selections = selections
    )

    implicit val subQueryLookupTable = Map(patternExp -> patternQG)

    implicit val context = newMockedLogicalPlanningContext(
      planContext = newMockedPlanContext,
      metrics = factory.newMetrics(hardcodedStatistics, newMockedSemanticTable)
    )

    val aPlan = newMockedQueryPlan("a")
    val singleRow = planArgumentRow(Set(IdName("a")))
    val inner = planExpand(singleRow, IdName("a"), dir, types, IdName(nodeName), IdName(relName), SimplePatternLength, patternRel)

    // When
    val result = selectPatternPredicates(passThrough)(aPlan, qg)

    // Then
    result should equal(solvePredicate(planSelectOrSemiApply(aPlan, inner, equals), orsExp))
  }

  test("should introduce select or anti semi apply for unsolved negated pattern predicates in disjunction with an expression") {
    // Given
    val equals = Equals(
      Property(ident("a"), PropertyKeyName("prop")_)_,
      StringLiteral("42")_
    )_
    val orsExp = Ors(Set(Not(patternExp)(pos), equals))_
    val orPredicate = Predicate(Set(IdName("a")), orsExp)
    val selections = Selections(Set(orPredicate))
    val patternQG = QueryGraph(
      patternRelationships = Set(patternRel),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName(nodeName))
    )

    val qg = QueryGraph(
      patternNodes = Set("a"),
      selections = selections
    )

    implicit val subQueryLookupTable = Map(patternExp -> patternQG)

    implicit val context = newMockedLogicalPlanningContext(
      planContext = newMockedPlanContext,
      metrics = factory.newMetrics(hardcodedStatistics, newMockedSemanticTable)
    )

    val aPlan = newMockedQueryPlan("a")
    val inner = planExpand(planArgumentRow(Set(IdName("a"))), IdName("a"), dir, types, IdName(nodeName), IdName(relName), SimplePatternLength, patternRel)

    // When
    val result = selectPatternPredicates(passThrough)(aPlan, qg)

    // Then
    result should equal(solvePredicate(planSelectOrAntiSemiApply(aPlan, inner, equals), orsExp))
  }

  test("should introduce let semi apply and select or semi apply for multiple pattern predicates in or") {
    // Given
    val patternExp2 = PatternExpression(RelationshipsPattern(RelationshipChain(
      NodePattern(Some(ident("a")), Seq(), None, naked = false)_,
      RelationshipPattern(Some(ident("  UNNAMED3")), optional = false, types, None, None, dir) _,
      NodePattern(Some(ident("  UNNAMED4")), Seq(LabelName("APA")_), None, naked = false)_
    )_)_)

    val patternRel2 = PatternRelationship("  UNNAMED3", ("a", "  UNNAMED4"), dir, types, SimplePatternLength)

    val orsExp = Ors(Set(patternExp, patternExp2))_
    val orPredicate = Predicate(Set(IdName("a")), orsExp)
    val selections = Selections(Set(orPredicate))
    val patternQG = QueryGraph(
      patternRelationships = Set(patternRel),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName(nodeName))
    )

    val patternQG2 = QueryGraph(
      patternRelationships = Set(patternRel2),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName("  UNNAMED4"))
    )

    val qg = QueryGraph(
      patternNodes = Set("a"),
      selections = selections
    )

    implicit val subQueryLookupTable = Map(patternExp -> patternQG, patternExp2 -> patternQG2)

    implicit val context = newMockedLogicalPlanningContext(
      planContext = newMockedPlanContext,
      metrics = factory.newMetrics(hardcodedStatistics, newMockedSemanticTable)
    )

    val aPlan = newMockedQueryPlan("a")
    val inner = planExpand(planArgumentRow(Set(IdName("a"))), IdName("a"), dir, types, IdName(nodeName), IdName(relName), SimplePatternLength, patternRel)
    val inner2 = planExpand(planArgumentRow(Set(IdName("a"))), IdName("a"), dir, types, IdName("  UNNAMED4"), IdName("  UNNAMED3"), SimplePatternLength, patternRel2)

    // When
    val result = selectPatternPredicates(passThrough)(aPlan, qg)

    // Then
    result should equal(solvePredicate(planSelectOrSemiApply(planLetSemiApply(aPlan, inner, IdName("  FRESHID0")), inner2, ident("  FRESHID0")), orsExp))
  }

  test("should introduce let semi apply and select or anti semi apply for multiple pattern predicates in or") {
    // Given
    val patternExp2: PatternExpression = PatternExpression(RelationshipsPattern(RelationshipChain(
      NodePattern(Some(ident("a")), Seq(), None, naked = false)_,
      RelationshipPattern(Some(ident("  UNNAMED3")), optional = false, types, None, None, dir) _,
      NodePattern(Some(ident("  UNNAMED4")), Seq(LabelName("APA")_), None, naked = false)_
    )_)_)
    val patternRel2 = PatternRelationship("  UNNAMED3", ("a", "  UNNAMED4"), dir, types, SimplePatternLength)

    val orsExp = Ors(Set(patternExp, Not(patternExp2)_))_
    val orPredicate = Predicate(Set(IdName("a")), orsExp)
    val selections = Selections(Set(orPredicate))
    val patternQG = QueryGraph(
      patternRelationships = Set(patternRel),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName(nodeName))
    )

    val patternQG2 = QueryGraph(
      patternRelationships = Set(patternRel2),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName("  UNNAMED4"))
    )

    val qg = QueryGraph(
      patternNodes = Set("a"),
      selections = selections
    )

    implicit val subQueryLookupTable = Map(patternExp -> patternQG, patternExp2 -> patternQG2)

    implicit val context = newMockedLogicalPlanningContext(
      planContext = newMockedPlanContext,
      metrics = factory.newMetrics(hardcodedStatistics, newMockedSemanticTable)
    )

    val aPlan = newMockedQueryPlan("a")
    val inner = planExpand(planArgumentRow(Set(IdName("a"))), IdName("a"), dir, types, IdName(nodeName), IdName(relName), SimplePatternLength, patternRel)
    val inner2 = planExpand(planArgumentRow(Set(IdName("a"))), IdName("a"), dir, types, IdName("  UNNAMED4"), IdName("  UNNAMED3"), SimplePatternLength, patternRel2)

    // When
    val result = selectPatternPredicates(passThrough)(aPlan, qg)

    // Then
    result should equal(solvePredicate(planSelectOrAntiSemiApply(planLetSemiApply(aPlan, inner, IdName("  FRESHID0")), inner2, ident("  FRESHID0")), orsExp))
  }

  test("should introduce let anti semi apply and select or semi apply for multiple pattern predicates in or") {
    // Given
    val patternExp2: PatternExpression = PatternExpression(RelationshipsPattern(RelationshipChain(
      NodePattern(Some(ident("a")), Seq(), None, naked = false)_,
      RelationshipPattern(Some(ident("  UNNAMED3")), optional = false, types, None, None, dir) _,
      NodePattern(Some(ident("  UNNAMED4")), Seq(LabelName("APA")_), None, naked = false)_
    )_)_)
    val patternRel2 = PatternRelationship("  UNNAMED3", ("a", "  UNNAMED4"), dir, types, SimplePatternLength)

    val orsExp = Ors(Set(Not(patternExp)_, patternExp2))_
    val orPredicate = Predicate(Set(IdName("a")), orsExp)
    val selections = Selections(Set(orPredicate))
    val patternQG = QueryGraph(
      patternRelationships = Set(patternRel),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName(nodeName))
    )

    val patternQG2 = QueryGraph(
      patternRelationships = Set(patternRel2),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName("  UNNAMED4"))
    )

    val qg = QueryGraph(
      patternNodes = Set("a"),
      selections = selections
    )

    implicit val subQueryLookupTable = Map(patternExp -> patternQG, patternExp2 -> patternQG2)

    implicit val context = newMockedLogicalPlanningContext(
      planContext = newMockedPlanContext,
      metrics = factory.newMetrics(hardcodedStatistics, newMockedSemanticTable)
    )

    val aPlan = newMockedQueryPlan("a")
    val inner = planExpand(planArgumentRow(Set(IdName("a"))), IdName("a"), dir, types, IdName(nodeName), IdName(relName), SimplePatternLength, patternRel)
    val inner2 = planExpand(planArgumentRow(Set(IdName("a"))), IdName("a"), dir, types, IdName("  UNNAMED4"), IdName("  UNNAMED3"), SimplePatternLength, patternRel2)

    // When
    val result = selectPatternPredicates(passThrough)(aPlan, qg)

    // Then
    result should equal(solvePredicate(planSelectOrSemiApply(planLetAntiSemiApply(aPlan, inner, IdName("  FRESHID0")), inner2, ident("  FRESHID0")), orsExp))
  }

  test("should introduce let select or semi apply and select or anti semi apply for multiple pattern predicates in or") {
    // Given
    val equals = Equals(
      Property(ident("a"), PropertyKeyName("prop")_)_,
      StringLiteral("42")_
    )_

    val patternExp2: PatternExpression = PatternExpression(RelationshipsPattern(RelationshipChain(
      NodePattern(Some(ident("a")), Seq(), None, naked = false)_,
      RelationshipPattern(Some(ident("  UNNAMED3")), optional = false, types, None, None, dir) _,
      NodePattern(Some(ident("  UNNAMED4")), Seq(LabelName("APA")_), None, naked = false)_
    )_)_)
    val patternRel2 = PatternRelationship("  UNNAMED3", ("a", "  UNNAMED4"), dir, types, SimplePatternLength)

    val orsExp = Ors(Set(equals, patternExp, Not(patternExp2)_))_
    val orPredicate = Predicate(Set(IdName("a")), orsExp)
    val selections = Selections(Set(orPredicate))
    val patternQG = QueryGraph(
      patternRelationships = Set(patternRel),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName(nodeName))
    )

    val patternQG2 = QueryGraph(
      patternRelationships = Set(patternRel2),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName("  UNNAMED4"))
    )

    val qg = QueryGraph(
      patternNodes = Set("a"),
      selections = selections
    )

    implicit val subQueryLookupTable = Map(patternExp -> patternQG, patternExp2 -> patternQG2)

    implicit val context = newMockedLogicalPlanningContext(
      planContext = newMockedPlanContext,
      metrics = factory.newMetrics(hardcodedStatistics, newMockedSemanticTable)
    )

    val aPlan = newMockedQueryPlan("a")
    val inner = planExpand(planArgumentRow(Set(IdName("a"))), IdName("a"), dir, types, IdName(nodeName), IdName(relName), SimplePatternLength, patternRel)
    val inner2 = planExpand(planArgumentRow(Set(IdName("a"))), IdName("a"), dir, types, IdName("  UNNAMED4"), IdName("  UNNAMED3"), SimplePatternLength, patternRel2)

    // When
    val result = selectPatternPredicates(passThrough)(aPlan, qg)

    // Then
    result should equal(solvePredicate(planSelectOrAntiSemiApply(planLetSelectOrSemiApply(aPlan, inner, IdName("  FRESHID0"), equals), inner2, ident("  FRESHID0")), orsExp))
  }

  test("should introduce let anti select or semi apply and select or semi apply for multiple pattern predicates in or") {
    // Given
    val equals = Equals(
      Property(ident("a"), PropertyKeyName("prop")_)_,
      StringLiteral("42")_
    )_

    val patternExp2: PatternExpression = PatternExpression(RelationshipsPattern(RelationshipChain(
      NodePattern(Some(ident("a")), Seq(), None, naked = false)_,
      RelationshipPattern(Some(ident("  UNNAMED3")), optional = false, types, None, None, dir) _,
      NodePattern(Some(ident("  UNNAMED4")), Seq(LabelName("APA")_), None, naked = false)_
    )_)_)
    val patternRel2 = PatternRelationship("  UNNAMED3", ("a", "  UNNAMED4"), dir, types, SimplePatternLength)

    val orsExp = Ors(Set(equals, Not(patternExp)_, patternExp2))_
    val orPredicate = Predicate(Set(IdName("a")), orsExp)
    val selections = Selections(Set(orPredicate))
    val patternQG = QueryGraph(
      patternRelationships = Set(patternRel),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName(nodeName))
    )

    val patternQG2 = QueryGraph(
      patternRelationships = Set(patternRel2),
      argumentIds = Set(IdName("a")),
      patternNodes = Set(IdName("a"), IdName("  UNNAMED4"))
    )

    val qg = QueryGraph(
      patternNodes = Set("a"),
      selections = selections
    )

    implicit val subQueryLookupTable = Map(patternExp -> patternQG, patternExp2 -> patternQG2)

    implicit val context = newMockedLogicalPlanningContext(
      planContext = newMockedPlanContext,
      metrics = factory.newMetrics(hardcodedStatistics, newMockedSemanticTable)
    )

    val aPlan = newMockedQueryPlan("a")
    val inner = planExpand(planArgumentRow(Set(IdName("a"))), IdName("a"), dir, types, IdName(nodeName), IdName(relName), SimplePatternLength, patternRel)
    val inner2 = planExpand(planArgumentRow(Set(IdName("a"))), IdName("a"), dir, types, IdName("  UNNAMED4"), IdName("  UNNAMED3"), SimplePatternLength, patternRel2)

    // When
    val result = selectPatternPredicates(passThrough)(aPlan, qg)

    // Then
    result should equal(solvePredicate(planSelectOrSemiApply(planLetSelectOrAntiSemiApply(aPlan, inner, IdName("  FRESHID0"), equals), inner2, ident("  FRESHID0")), orsExp))
  }
}