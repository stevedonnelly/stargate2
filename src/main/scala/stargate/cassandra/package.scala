/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stargate 

import java.net.InetSocketAddress
import java.util.concurrent.CompletionStage

import com.datastax.oss.driver.api.core.cql.{AsyncResultSet, Row, SimpleStatement, Statement}
import com.datastax.oss.driver.api.core.{CqlSession, CqlSessionBuilder}
import com.datastax.oss.driver.api.querybuilder.SchemaBuilder
import com.datastax.oss.driver.api.querybuilder.schema.CreateTable
import com.datastax.oss.driver.internal.core.util.Strings
import stargate.util.AsyncList

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._
import stargate.service.config.ParsedStargateConfig
import com.typesafe.scalalogging.LazyLogging

/**
  * provides most of the cassandra query methods and schema modification support
  */
package object cassandra extends LazyLogging {
  val schemaOpTimeout: java.time.Duration = java.time.Duration.ofSeconds(10)
  type PagedResults[T] = AsyncList[T]

  def convertAsyncResultSet(resultSet: Future[AsyncResultSet], executor: ExecutionContext): PagedResults[Row] = {
    AsyncList.unfuture(resultSet.map(ars => {
      val head = AsyncList.fromList(ars.currentPage().asScala.toList)
      val tail = if (ars.hasMorePages) {
        () => convertAsyncResultSet(ars.fetchNextPage().asScala, executor)
      } else {
        () => AsyncList.empty[Row]
      }
      AsyncList.append(head, tail, executor)
    })(executor), executor)
  }

  def convertAsyncResultSet(resultSet: CompletionStage[AsyncResultSet], executor: ExecutionContext): PagedResults[Row] = {
    convertAsyncResultSet(resultSet.asScala, executor)
  }

  /**
    * map row to an object map
    *
    * @param row row from the database
    * @return generic map of maps where any nested objects will also be type Map[String, Object]
    */
  def rowToMap(row: Row): Map[String, Object] = {
    row.getColumnDefinitions.iterator.asScala.map(col => (col.getName.toString, row.getObject(col.getName))).toMap
  }

  /**
    * Provides paging of the result set
    *
    * @param cqlSession active CQL session
    * @param statement CQL statement to execute
    * @param executor executor thread pool
    * @return paged results so that rows can be retrieved in steps instead of all at once
    */
  def queryAsync(cqlSession: CqlSession, statement: Statement[_], executor: ExecutionContext): PagedResults[Row] = {
    convertAsyncResultSet(cqlSession.executeAsync(statement), executor)
  }

  /**
    * executes a generic CQL query in an asynchronous fashion
    *
    * @param cqlSession active CQL session
    * @param statement CQL statement to execute
    * @param executor executor thread pool
    * @return a future to execute at a later time
    */
  def executeAsync(cqlSession: CqlSession, statement: Statement[_], executor: ExecutionContext): Future[Unit] = {
    queryAsync(cqlSession, statement, executor).toList(executor).map(_ => ())(executor)
  }

  /**
    * Create table statement that is built from the CassandraTable
    *
    * @param table represents the Apache Cassandra table that will be created
    * @return a SimpleStatement which can be passed to execute or executeAsync for execution
    */
  def createTableStatement(table: CassandraTable): SimpleStatement = {
    val base = SchemaBuilder.createTable(Strings.doubleQuote(table.keyspace), Strings.doubleQuote(table.name)).ifNotExists().asInstanceOf[CreateTable]
    val partitionKeys = table.columns.key.partitionKeys.foldLeft(base)((builder, next) => builder.withPartitionKey(Strings.doubleQuote(next.name), next.`type`))
    val clusteringKeys = table.columns.key.clusteringKeys.foldLeft(partitionKeys)((builder, next) => builder.withClusteringColumn(Strings.doubleQuote(next.name), next.`type`))
    val dataCols = table.columns.data.foldLeft(clusteringKeys)((builder, next) => builder.withColumn(Strings.doubleQuote(next.name), next.`type`))
    dataCols.build.setTimeout(schemaOpTimeout)
  }

  /**
    * 
    * creates a new table but do so asynchronously
    * 
    * @param session active CQL session
    * @param table table to create. Keyspace is part of the CassandraTable object
    * @return the future to be executed at a later date
    */
  def createTableAsync(session: CqlSession, table: CassandraTable): Future[AsyncResultSet] = {
    session.executeAsync(createTableStatement(table)).asScala
  }

  /**
    * creates a new table
    *
    * @param session active CQL session
    * @param table table to create. Keyspace is part of the CassandraTable object
    * @return the future result which still needs to be processed
    */
  def createTable(session: CqlSession, table: CassandraTable): AsyncResultSet = util.await(createTableAsync(session, table)).get

  /**
    * entry point to configure a connection to Apache Cassandra
    *
    * @param contacts list of nodes running Apache Cassandra. It does not have to be all nodes as only a single node is needed to discover the rest of the datacenter connected to
    * @param dataCenter Apache Cassandra data center to connect to
    * @return a builder instance for the CqlSession
    */
  def sessionBuilder(contacts: List[(String, Int)], dataCenter: String): CqlSessionBuilder = {
    val builder = contacts.foldLeft(CqlSession.builder)((builder, contact) => builder.addContactPoint(InetSocketAddress.createUnresolved(contact._1, contact._2)))
    builder.withLocalDatacenter(dataCenter)
  }

  /**
    * main entry point to create a session for Apache Cassandra. There should only
    * be one of those per Apache Cassandra cluster
    *
    * @param sgConfig parsed configuration to use. Sources from two hocon files merged together defaults.conf and stargate-docker.conf in the src/main/resources folder
    * @return an active CqlSession it must be shut down when stopping the application
    */
  def session(sgConfig: ParsedStargateConfig): CqlSession = {
      val contacts: List[(String, Int)] = sgConfig.cassandraContactPoints
      val dataCenter: String = sgConfig.cassandraDataCenter
      sgConfig.cassandraAuthProvider match {
          //TODO add support for authorization-id when we have newer driver version
          case "PlainTextAuthProvider" => {
            logger.info(s"logging into dc $dataCenter with plain text authentication $contacts")
            sessionBuilder(contacts, dataCenter)
             .withAuthCredentials(sgConfig.cassandraUserName, sgConfig.cassandraPassword)
             .build
          }
          case "" => {
            logger.info(s"logging into dc $dataCenter with no authentication")
            sessionBuilder(contacts, dataCenter).build
          }
          case _ => {
             val authProvider = Class.forName(sgConfig.cassandraAuthProvider)
             .asInstanceOf[com.datastax.oss.driver.api.core.auth.AuthProvider]
             logger.info(s"logging into dc $dataCenter with custom auth provider ${sgConfig.cassandraAuthProvider}")
             sessionBuilder(contacts, dataCenter)
                .withAuthProvider(authProvider).build
          }
      }
  }

  /**
    * creates a new keyspace but does so asynchronously
    *
    * @param session active CqlSession
    * @param name keyspace to create
    * @param replication replication factor to use
    * @param executor executor thread pool to use
    * @return future to execute later
    */
  def createKeyspaceAsync(session: CqlSession, name: String, replication: Int, executor: ExecutionContext): Future[Unit] = {
    val create = session.executeAsync(SchemaBuilder.createKeyspace(Strings.doubleQuote(name)).ifNotExists().withSimpleStrategy(replication).build.setTimeout(schemaOpTimeout)).asScala
    val agreement = create.flatMap(_ => session.checkSchemaAgreementAsync().asScala)(executor)
    agreement.map(require(_, s"failed to reach schema agreement after creating keyspace: ${name}"))(executor)
  }

  /**
    * creates a new keyspace
    *
    * @param session active CqlSession
    * @param name keyspace to create
    * @param replication replication factor to use
    */
  def createKeyspace(session: CqlSession, name: String, replication: Int): Unit = util.await(createKeyspaceAsync(session, name, replication, util.newCachedExecutor)).get

  /**
    * deletes the entire keyspace specified but does so asynchronously
    *
    * @param session active CqlSession
    * @param keyspace keyspace to wipe and reload
    * @param executor executor thread pool to use
    * @return a future to execute later
    */
  def wipeKeyspaceAsync(session: CqlSession, keyspace: String, executor: ExecutionContext): Future[Unit] = {
    val delete = session.executeAsync(SchemaBuilder.dropKeyspace(Strings.doubleQuote(keyspace)).ifExists().build.setTimeout(schemaOpTimeout)).asScala
    val agreement = delete.flatMap(_ => session.checkSchemaAgreementAsync().asScala)(executor)
    agreement.map(require(_, s"failed to reach schema agreement after deleting keyspace: ${keyspace}"))(executor)
  }

  /**
    * deletes the entire keyspace specified
    *  
    * @param session active CqlSession
    * @param keyspace keyspace to remove
    */
  def wipeKeyspace(session: CqlSession, keyspace: String): Unit = util.await(wipeKeyspaceAsync(session, keyspace, util.newCachedExecutor)).get

  /**
    * wipes the keyspace and then creates it but does so asynchronously
    *
    * @param session active CqlSession
    * @param keyspace keyspace to wipe and reload
    * @param replication replication factor to specify on the new keyspace
    * @param executor executor thread pool to use
    * @return a future to execute later
    */
  def recreateKeyspaceAsync(session: CqlSession, keyspace: String, replication: Int, executor: ExecutionContext): Future[Unit] = {
    wipeKeyspaceAsync(session, keyspace, executor).flatMap(_ => createKeyspaceAsync(session, keyspace, replication, executor))(executor)
  }

  /**
    * wipes the keyspace and then creates it 
    *
    * @param session active CqlSession
    * @param keyspace keyspace to wipe and reload
    * @param replication replication factor to specify on the new keyspace
    */
  def recreateKeyspace(session: CqlSession, keyspace: String, replication: Int): Unit = util.await(recreateKeyspaceAsync(session, keyspace, replication, util.newCachedExecutor)).get
}