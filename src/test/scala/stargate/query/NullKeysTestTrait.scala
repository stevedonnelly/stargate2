package stargate.query

import com.typesafe.config.ConfigFactory
import org.junit.Test
import stargate.{CassandraTest, CassandraTestSession, keywords, schema, util}
import stargate.model.OutputModel

import scala.concurrent.ExecutionContext

trait NullKeysTestTrait extends CassandraTestSession {

  def checkPerfectMatch(model: OutputModel, entityName: String, conditions: List[Object]): Unit = {
    val groupedConditions = stargate.model.queries.parser.parseConditions(model.input.entities, List.empty, entityName, conditions)
    groupedConditions.foreach(pathConds => {
      val (path, conditions) = pathConds
      val targetEntityName = schema.traverseEntityPath(model.input.entities, entityName, path)
      val scores = schema.tableScores(conditions.map(_.named), model.entityTables(targetEntityName))
      assert(scores.keys.min.perfect)
    })
  }

  def longOf(l: Long): Object = java.lang.Long.valueOf(l)

  @Test
  def testNullKey: Unit = {
    val executor = ExecutionContext.global
    val keyspace = this.newKeyspace
    val model = schema.outputModel(stargate.model.parser.parseModel(ConfigFactory.parseResources("optional-keys.conf")), keyspace)
    val viewTable = model.entityTables("foo").filter(t => t.name != schema.baseTableName("foo")).head
    val keys = viewTable.columns.key.names.combined
    util.await(model.createTables(session, executor)).get

    val foos = List[Map[String,Object]](
      Map((keys(0), longOf(1)), (keys(1), longOf(1)), (keys(2), longOf(1))),
      Map((keys(0), longOf(1)), (keys(1), longOf(1))),
      Map((keys(0), longOf(1)), (keys(1), longOf(2)))
    )
    val res = util.await(util.sequence(foos.map(foo => model.mutation.create("foo", foo, session, executor)), executor)).get

    val conds1 = List[Object](keys(0), "=", longOf(1), keys(1), "=", longOf(1))
    val query1 = Map((keywords.mutation.MATCH, conds1))
    checkPerfectMatch(model, "foo", conds1)
    val res1 = util.await(model.get("foo", query1, session, executor).toList(executor)).get
    assert(res1.length == 2)

    val conds2 = List[Object](keys(0), "=", longOf(1), keys(1), "IN", List(longOf(1), longOf(2)))
    val query2 = Map((keywords.mutation.MATCH, conds2))
    checkPerfectMatch(model, "foo", conds2)
    val res2 = util.await(model.get("foo", query2, session, executor).toList(executor)).get
    assert(res2.length == 3)

  }

}