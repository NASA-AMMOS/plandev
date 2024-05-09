package gov.nasa.jpl.aerie.timeline.plan

import java.sql.Connection
import java.sql.PreparedStatement

fun getSingleLongQueryResult(statement: PreparedStatement): Long {
  val result = statement.executeQuery()
  if (!result.next()) throw AeriePostgresSimulationResults.DatabaseError("Expected exactly one result for query, found none: $statement")
  val int = result.getLong(1)
  if (result.next()) throw AeriePostgresSimulationResults.DatabaseError("Expected exactly one result for query, found more than one: $statement")
  return int
}

fun intervalStyleStatement(c: Connection): PreparedStatement = c.prepareStatement("set intervalstyle = 'iso_8601';")
