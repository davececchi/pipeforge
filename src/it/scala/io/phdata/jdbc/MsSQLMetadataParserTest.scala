package io.phdata.jdbc

import java.sql.{JDBCType, ResultSet}

import com.whisk.docker.DockerContainer
import io.phdata.jdbc.config.{DatabaseConf, DatabaseType, ObjectType}
import io.phdata.jdbc.domain.{Column, Table}
import io.phdata.jdbc.parsing.{DatabaseMetadataParser, MsSQLMetadataParser}

import scala.util.{Failure, Success}

/**
  * Microsoft SQL Server integration tests
  */
class MsSQLMetadataParserTest extends DockerTestRunner {

  import scala.concurrent.duration._

  // Database Properties
  override val PASSWORD = "!IntegrationTests"
  override val DATABASE = "master"
  override val USER = "SA"
  override val TABLE = "it_table"
  override val VIEW = "it_view"
  override val NO_RECORDS_TABLE = "no_records"
  // Container Properites
  override val IMAGE = "microsoft/mssql-server-linux:latest"
  override val ADVERTISED_PORT = 1433
  override val EXPOSED_PORT = 1433

  override lazy val CONTAINER = DockerContainer(IMAGE)
    .withPorts((ADVERTISED_PORT, Some(EXPOSED_PORT)))
    .withEnv("ACCEPT_EULA=Y", s"SA_PASSWORD=$PASSWORD")

  override val URL = s"jdbc:sqlserver://${CONTAINER.hostname.getOrElse("localhost")}:$EXPOSED_PORT;database=$DATABASE"
  override val DRIVER = "com.microsoft.sqlserver.jdbc.SQLServerDriver"

  private lazy val DOCKER_CONFIG = new DatabaseConf(DatabaseType.MSSQL,
    DATABASE,
    URL,
    USER,
    PASSWORD,
    ObjectType.TABLE
  )

   private lazy val CONNECTION = DatabaseMetadataParser.getConnection(DOCKER_CONFIG).get

  override def beforeAll(): Unit = {
    super.beforeAll()
    CONTAINER.withReadyChecker(
      new DatabaseReadyChecker(
        DRIVER,
        URL,
        USER,
        PASSWORD,
        DATABASE).looped(30, 5.seconds)
    )
    startAllOrFail()
    Thread.sleep(5000)
    createTestTable()
    createEmptyTable()
    insertTestData()
    createTestView()
  }

  override def afterAll(): Unit = {
    stopAllQuietly()
    super.afterAll()
  }

  test("run query against database") {
    val stmt = CONNECTION.createStatement()
    val rs: ResultSet = stmt.executeQuery("SELECT * FROM sys.tables")
    val results = getResults(rs)(x => x.getString(1)).toList
    assertResult(6)(results.length)
  }

  test("parse tables metadata") {
    val parser = new MsSQLMetadataParser(CONNECTION)
    parser.getTablesMetadata(ObjectType.TABLE, DATABASE, Some(Set(TABLE))) match {
      case Success(definitions) =>
        assert(definitions.size == 1)
        val expected = Set(
          Table(TABLE,
          Set(
            Column("ID",JDBCType.INTEGER,false,1,10,0),
            Column("LastName",JDBCType.VARCHAR,false,2,255,0)),
          Set(
            Column("FirstName",JDBCType.VARCHAR,true,3,255,0),
            Column("Age",JDBCType.INTEGER,true,4,10,0)
          )))
        assertResult(expected)(definitions)

      case Failure(ex) =>
        logger.error("Error gathering metadata from source", ex)
    }
  }

  test("parse views metadata") {
    val parser = new MsSQLMetadataParser(CONNECTION)
    parser.getTablesMetadata(ObjectType.VIEW, DATABASE, Some(Set(VIEW))) match {
      case Success(definitions) =>
        assert(definitions.size == 1)
        val expected = Set(
          Table(VIEW,
            Set(),
            Set(
              Column("ID",JDBCType.INTEGER,false,1,10,0),
              Column("LastName",JDBCType.VARCHAR,false,2,255,0),
              Column("FirstName",JDBCType.VARCHAR,true,3,255,0),
              Column("Age",JDBCType.INTEGER,true,4,10,0)))
        )
        assertResult(expected)(definitions)
      case Failure(ex) =>
        logger.error("Error gathering metadata from source", ex)
    }
  }

  test("skip tables with no records") {
    val parser = new MsSQLMetadataParser(CONNECTION)
    parser.getTablesMetadata(ObjectType.TABLE, DATABASE, Some(Set(NO_RECORDS_TABLE))) match {
      case Success(definitions) =>
        assert(definitions.isEmpty)
      case Failure(ex) =>
        logger.error("Error gathering metadata from source", ex)
    }
  }

  private def createTestDatabase(): Unit = {
    val query =
      s"""
         |CREATE DATABASE $DATABASE
       """.stripMargin
    val stmt = CONNECTION.createStatement()
    stmt.execute(query)
  }

  private def createTestTable(): Unit = {
    val query =
      s"""
         |CREATE TABLE $TABLE (
         |  ID int NOT NULL,
         |  LastName varchar(255) NOT NULL,
         |  FirstName varchar(255),
         |  Age int,
         |  CONSTRAINT PK_Person PRIMARY KEY (ID,LastName)
         |)
       """.stripMargin
    val stmt = CONNECTION.createStatement()
    stmt.execute(query)
  }

  private def createEmptyTable(): Unit = {
    val query =
      s"""
         |CREATE TABLE $NO_RECORDS_TABLE (
         |  ID int NOT NULL,
         |  LastName varchar(255) NOT NULL,
         |  FirstName varchar(255)
         |  CONSTRAINT PK_no_records PRIMARY KEY (ID)
         |)
       """.stripMargin
    val stmt = CONNECTION.createStatement()
    stmt.execute(query)
  }

  private def insertTestData(): Unit = {
    val query =
      s"""
         |INSERT INTO $TABLE
         |  (ID, LastName, FirstName, Age)
         |VALUES
         |  (1, 'developer', 'phdata', 1)
       """.stripMargin
    val stmt = CONNECTION.createStatement()
    stmt.execute(query)
  }

  private def createTestView(): Unit = {
    val query =
      s"""
         |CREATE VIEW $VIEW AS SELECT * FROM $TABLE
       """.stripMargin
    val stmt = CONNECTION.createStatement()
    stmt.execute(query)
  }
}
