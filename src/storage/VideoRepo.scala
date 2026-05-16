package kidstv.storage

import com.augustnagro.magnum.*
import io.circe.syntax.*
import io.circe.parser.decode
import java.time.Instant
import kidstv.domain.*
import kidstv.domain.Codecs.given

object VideoRepo:

  def insert(video: Video)(using DbCon): Unit =
    val statusJson = video.status.asJson.noSpaces
    val tagsJson = video.tags.map(_.asJson.noSpaces)
    val createdAt = video.createdAt.toString
    val updatedAt = video.updatedAt.toString
    sql"""INSERT INTO videos (id, source_url, title, runtime_seconds, status, tags, created_at, updated_at)
          VALUES (${video.id}, ${video.sourceUrl}, ${video.title}, ${video.runtimeSeconds},
                  $statusJson, $tagsJson, $createdAt, $updatedAt)""".update.run()

  def findByUrl(url: String)(using DbCon): Option[Video] =
    sql"SELECT id, source_url, title, runtime_seconds, status, tags, created_at, updated_at FROM videos WHERE source_url = $url"
      .query[(String, String, String, Option[Int], String, Option[String], String, String)]
      .run()
      .headOption
      .map(fromRow)

  def findById(id: String)(using DbCon): Option[Video] =
    sql"SELECT id, source_url, title, runtime_seconds, status, tags, created_at, updated_at FROM videos WHERE id = $id"
      .query[(String, String, String, Option[Int], String, Option[String], String, String)]
      .run()
      .headOption
      .map(fromRow)

  def listAll()(using DbCon): Vector[Video] =
    sql"SELECT id, source_url, title, runtime_seconds, status, tags, created_at, updated_at FROM videos ORDER BY created_at DESC"
      .query[Row]
      .run()
      .map(fromRow)

  def listByFilters(status: Option[String], showName: Option[String])(using DbCon): Vector[Video] =
    val statusFilter = status.map(_.capitalize)
    val namePattern = showName.map(n => s"%${n.toLowerCase}%")
    (statusFilter, namePattern) match
      case (Some(s), Some(p)) =>
        sql"""SELECT id, source_url, title, runtime_seconds, status, tags, created_at, updated_at
              FROM videos WHERE json_extract(status, '$$.type') = $s AND lower(title) LIKE $p
              ORDER BY title"""
          .query[Row].run().map(fromRow)
      case (Some(s), None) =>
        sql"""SELECT id, source_url, title, runtime_seconds, status, tags, created_at, updated_at
              FROM videos WHERE json_extract(status, '$$.type') = $s
              ORDER BY title"""
          .query[Row].run().map(fromRow)
      case (None, Some(p)) =>
        sql"""SELECT id, source_url, title, runtime_seconds, status, tags, created_at, updated_at
              FROM videos WHERE lower(title) LIKE $p
              ORDER BY title"""
          .query[Row].run().map(fromRow)
      case (None, None) =>
        listAll()

  def updateStatus(id: String, status: VideoStatus, updatedAt: Instant)(using DbCon): Boolean =
    val statusJson = status.asJson.noSpaces
    val at = updatedAt.toString
    sql"UPDATE videos SET status = $statusJson, updated_at = $at WHERE id = $id".update.run() > 0

  def updateTags(id: String, tags: VideoTags, updatedAt: Instant)(using DbCon): Boolean =
    val tagsJson = tags.asJson.noSpaces
    val at = updatedAt.toString
    sql"UPDATE videos SET tags = $tagsJson, updated_at = $at WHERE id = $id".update.run() > 0

  def listApproved()(using DbCon): Vector[Video] =
    sql"""SELECT id, source_url, title, runtime_seconds, status, tags, created_at, updated_at
          FROM videos WHERE json_extract(status, '$$.type') = 'Approved'
          ORDER BY title"""
      .query[Row].run().map(fromRow)

  def listCandidates()(using DbCon): Vector[Video] =
    sql"""SELECT id, source_url, title, runtime_seconds, status, tags, created_at, updated_at
          FROM videos WHERE json_extract(status, '$$.type') = 'Candidate'
          ORDER BY created_at"""
      .query[Row].run().map(fromRow)

  private type Row = (String, String, String, Option[Int], String, Option[String], String, String)

  private def fromRow(r: Row): Video =
    val (id, sourceUrl, title, runtime, statusJson, tagsJson, createdAt, updatedAt) = r
    Video(
      id = id,
      sourceUrl = sourceUrl,
      title = title,
      runtimeSeconds = runtime,
      status = decode[VideoStatus](statusJson).fold(e => throw RuntimeException(s"Bad status JSON: $e"), identity),
      tags = tagsJson.flatMap(j => decode[VideoTags](j).toOption),
      createdAt = Instant.parse(createdAt),
      updatedAt = Instant.parse(updatedAt)
    )
