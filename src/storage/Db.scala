package kidstv.storage

import cats.effect.IO
import com.augustnagro.magnum.*
import javax.sql.DataSource
import org.sqlite.SQLiteDataSource

object Db:

  private val schemaDDL = List(
    """CREATE TABLE IF NOT EXISTS videos (
      |  id TEXT PRIMARY KEY,
      |  source_url TEXT NOT NULL UNIQUE,
      |  title TEXT NOT NULL,
      |  runtime_seconds INTEGER,
      |  status TEXT NOT NULL,
      |  tags TEXT,
      |  created_at TEXT NOT NULL,
      |  updated_at TEXT NOT NULL
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS external_metadata (
      |  video_id TEXT NOT NULL REFERENCES videos(id),
      |  source TEXT NOT NULL,
      |  fetched_at TEXT NOT NULL,
      |  raw_json TEXT NOT NULL,
      |  PRIMARY KEY (video_id, source)
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS tag_overrides (
      |  video_id TEXT NOT NULL REFERENCES videos(id),
      |  field_name TEXT NOT NULL,
      |  value TEXT NOT NULL,
      |  PRIMARY KEY (video_id, field_name)
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS play_history (
      |  id INTEGER PRIMARY KEY AUTOINCREMENT,
      |  video_id TEXT NOT NULL REFERENCES videos(id),
      |  played_at TEXT NOT NULL,
      |  session_id TEXT REFERENCES sessions(id),
      |  completed INTEGER NOT NULL DEFAULT 0
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS sessions (
      |  id TEXT PRIMARY KEY,
      |  built_at TEXT NOT NULL,
      |  target_duration_seconds INTEGER NOT NULL,
      |  mood_filter TEXT,
      |  video_ids TEXT NOT NULL
      |)""".stripMargin
  )

  def create(path: String): IO[DataSource] =
    IO.blocking:
      val ds = SQLiteDataSource()
      ds.setUrl(s"jdbc:sqlite:$path")
      ds.setEnforceForeignKeys(true)
      ds

  def bootstrap(ds: DataSource): IO[Unit] =
    IO.blocking:
      val conn = ds.getConnection()
      try
        val stmt = conn.createStatement()
        for ddl <- schemaDDL do stmt.execute(ddl)
        stmt.close()
      finally conn.close()

  def withDb[A](path: String)(f: DataSource => IO[A]): IO[A] =
    for
      ds <- create(path)
      _  <- bootstrap(ds)
      a  <- f(ds)
    yield a

  def transactIO[A](ds: DataSource)(f: DbCon ?=> A): IO[A] =
    IO.blocking(transact(ds)(f))

  def connectIO[A](ds: DataSource)(f: DbCon ?=> A): IO[A] =
    IO.blocking(connect(ds)(f))
