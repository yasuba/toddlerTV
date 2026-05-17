package kidstv.cli

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import com.monovore.decline.*
import javax.sql.DataSource
import kidstv.domain.*
import kidstv.enrich.{RuleBasedTagger, TagExtractor}
import kidstv.fetch.YtDlp
import kidstv.session.Builder
import kidstv.storage.{Db, VideoRepo, MetadataRepo, TagOverrideRepo, HistoryRepo}
import java.time.Instant
import java.util.UUID

object Commands:

  // -- add -------------------------------------------------------------------

  val addOpts: Opts[String] = Opts.argument[String](metavar = "url")

  def addRun(ds: DataSource, url: String): IO[ExitCode] =
    Db.connectIO(ds)(VideoRepo.findByUrl(url)).flatMap:
      case Some(v) =>
        IO.println(Output.printVideo(v)) *>
        IO.println("\nAlready in library.") *>
        IO.pure(ExitCode(1))
      case None =>
        for
          meta <- YtDlp.fetchMetadata(url)
          now  <- IO.realTimeInstant
          id    = slugify(meta.title)
          video = Video(
            id = id,
            sourceUrl = url,
            title = meta.title,
            runtimeSeconds = meta.durationSeconds,
            status = VideoStatus.Candidate(now),
            tags = None,
            createdAt = now,
            updatedAt = now
          )
          _ <- Db.transactIO(ds):
            VideoRepo.insert(video)
            MetadataRepo.insert(video.id, "youtube", meta.rawJson)
          _ <- IO.println(s"Added:\n${Output.printVideo(video)}")
        yield ExitCode.Success

  private def slugify(s: String): String =
    s.toLowerCase
      .replaceAll("[^a-z0-9]+", "-")
      .replaceAll("-+", "-")
      .stripPrefix("-")
      .stripSuffix("-")
      .take(80)

  // -- enrich ----------------------------------------------------------------

  private val enrichId = Opts.option[String]("id", "Video ID to enrich")
  private val enrichAll = Opts.flag("all-candidates", "Enrich all candidates").orFalse

  val enrichOpts: Opts[(Option[String], Boolean)] = (enrichId.orNone, enrichAll).tupled

  def enrichRun(ds: DataSource, id: Option[String], all: Boolean): IO[ExitCode] =
    for
      rules  <- RuleBasedTagger.loadRules(os.pwd / "config" / "tag-rules.json")
      videos <- resolveEnrichTargets(ds, id, all)
      _      <- if videos.isEmpty then IO.println("No videos to enrich.")
                else IO.println(s"Enriching ${videos.size} video(s)...") *>
                     videos.traverse_(v => enrichOne(ds, rules, v))
    yield ExitCode.Success

  private def resolveEnrichTargets(ds: DataSource, id: Option[String], all: Boolean): IO[Vector[Video]] =
    (id, all) match
      case (Some(videoId), _) =>
        Db.connectIO(ds)(VideoRepo.findById(videoId)).flatMap:
          case Some(v) => IO.pure(Vector(v))
          case None    => IO.raiseError(RuntimeException(s"Video not found: $videoId"))
      case (None, true) =>
        Db.connectIO(ds)(VideoRepo.listCandidates())
      case (None, false) =>
        IO.raiseError(RuntimeException("Specify --id <id> or --all-candidates"))

  private def enrichOne(ds: DataSource, rules: RuleBasedTagger.TagRules, video: Video): IO[Unit] =
    val action = for
      metadata  <- Db.connectIO(ds)(MetadataRepo.findByVideoId(video.id))
      _         <- if metadata.isEmpty then IO.raiseError(RuntimeException("No metadata found — run 'add' first"))
                   else IO.unit
      metaMap    = metadata.toMap
      tags      <- RuleBasedTagger.extractTags(rules, metaMap)
      overrides <- Db.connectIO(ds)(TagOverrideRepo.findByVideoId(video.id))
      finalTags  = if overrides.nonEmpty then TagExtractor.applyOverrides(tags, overrides) else tags
      now       <- IO.realTimeInstant
      _         <- Db.transactIO(ds)(VideoRepo.updateTags(video.id, finalTags, now))
      _         <- IO.println(s"  ✓ ${video.id}: pacing=${finalTags.pacing} type=${finalTags.contentType} moods=${finalTags.moods.mkString(",")}")
    yield ()
    action.handleErrorWith: err =>
      IO.println(s"  ✗ ${video.id}: ${err.getMessage}")

  // -- review ----------------------------------------------------------------

  val reviewOpts: Opts[Unit] = Opts.unit

  def reviewRun(ds: DataSource): IO[ExitCode] =
    kidstv.review.ReviewLoop.run(ds)

  // -- sync ------------------------------------------------------------------

  val syncOpts: Opts[Unit] = Opts.unit

  def syncRun(ds: DataSource): IO[ExitCode] =
    for
      _      <- IO.blocking(os.makeDir.all(os.pwd / "videos"))
      videos <- Db.connectIO(ds)(VideoRepo.listAll())
      _      <- IO.println(s"Syncing ${videos.size} video(s)...")
      _      <- videos.traverse_(syncOne)
    yield ExitCode.Success

  private def findVideoFile(id: String): IO[Option[os.Path]] =
    IO.blocking:
      val dir = os.pwd / "videos"
      if os.exists(dir) then os.list(dir).find(_.last.startsWith(s"$id."))
      else None

  private def syncOne(video: Video): IO[Unit] =
    video.status match
      case _: VideoStatus.Approved | _: VideoStatus.Candidate | _: VideoStatus.Reviewing =>
        findVideoFile(video.id).flatMap:
          case Some(_) =>
            IO.println(s"  . ${video.id} (exists)")
          case None =>
            IO.println(s"  ↓ ${video.id} (downloading...)") *>
            YtDlp.download(video.sourceUrl, s"videos/${video.id}.%(ext)s") *>
            IO.println(s"  ✓ ${video.id} (done)")
        .handleErrorWith: err =>
          IO.println(s"  ✗ ${video.id}: ${err.getMessage}")
      case _: VideoStatus.Rejected | _: VideoStatus.Retired =>
        findVideoFile(video.id).flatMap:
          case Some(path) =>
            IO.blocking(os.remove(path)) *>
            IO.println(s"  ✕ ${video.id} (deleted)")
          case None =>
            IO.println(s"  . ${video.id} (no file)")

  // -- build-session ---------------------------------------------------------

  private val duration = Opts.option[String]("duration", "Target duration, e.g. 45m").withDefault("30m")
  private val mood = Opts.option[String]("mood", "Mood filter").orNone
  private val output = Opts.option[String]("output", "Output directory").orNone

  val buildSessionOpts: Opts[(String, Option[String], Option[String])] = (duration, mood, output).tupled

  def buildSessionRun(ds: DataSource, dur: String, m: Option[String], out: Option[String]): IO[ExitCode] =
    for
      targetSeconds <- IO.fromOption(parseDuration(dur)):
        RuntimeException(s"Invalid duration: $dur (use e.g. 30m, 1h, 45m)")
      moodFilter <- IO.pure(m.map(s => Mood.valueOf(s.capitalize)))
        .handleErrorWith(_ => IO.raiseError(RuntimeException(s"Invalid mood: ${m.get}. Valid: ${Mood.values.mkString(", ")}")))
      approved    <- Db.connectIO(ds)(VideoRepo.listApproved())
      recent      <- Db.connectIO(ds)(HistoryRepo.recentSessionVideoIds(3))
      config       = Builder.Config(targetSeconds, moodFilter, recent)
      selected     = Builder.selectVideos(approved, config)
      result <-
        if selected.isEmpty then
          IO.println("No eligible videos found for these constraints.") *> IO.pure(ExitCode(1))
        else
          for
            now       <- IO.realTimeInstant
            sessionId  = UUID.randomUUID().toString.take(8)
            session    = Session(
              id = sessionId,
              builtAt = now,
              targetDurationSeconds = targetSeconds,
              moodFilter = moodFilter,
              videoIds = selected.map(_.id).toList
            )
            _ <- Db.transactIO(ds)(HistoryRepo.insertSession(session))
            totalSec = selected.flatMap(_.runtimeSeconds).sum
            _ <- IO.println(s"Session $sessionId: ${selected.size} videos, ${Output.formatDuration(totalSec)} total")
            _ <- selected.traverse_(v => IO.println(s"  ${v.id}  [${v.runtimeSeconds.map(Output.formatDuration).getOrElse("?")}]  ${v.title}"))
            _ <- writePlaylist(selected, out)
          yield ExitCode.Success
    yield result

  private def parseDuration(s: String): Option[Int] =
    val pattern = """(\d+)\s*(m|min|h|hr)""".r
    s.toLowerCase match
      case pattern(n, "m" | "min") => Some(n.toInt * 60)
      case pattern(n, "h" | "hr")  => Some(n.toInt * 3600)
      case _ => scala.util.Try(s.toInt).toOption // raw seconds

  private def writePlaylist(videos: Vector[Video], outputDir: Option[String]): IO[Unit] =
    for
      // Write m3u playlist
      lines <- IO.pure:
        videos.flatMap: v =>
          findVideoFilePath(v.id) match
            case Some(path) => Vector(s"#EXTINF:${v.runtimeSeconds.getOrElse(-1)},${v.title}", path)
            case None       => Vector.empty
      playlist = "#EXTM3U\n" + lines.mkString("\n") + "\n"
      _ <- IO.blocking(os.write.over(os.pwd / "current-session.m3u", playlist))
      _ <- IO.println(s"Playlist written: current-session.m3u")
      // Optionally symlink into output dir
      _ <- outputDir match
        case Some(dir) =>
          IO.blocking {
            val outPath = os.Path(dir, os.pwd)
            os.makeDir.all(outPath)
            videos.foreach: v =>
              findVideoFilePath(v.id).foreach: srcStr =>
                val src = os.Path(srcStr, os.pwd)
                val dest = outPath / src.last
                if !os.exists(dest) then os.symlink(dest, src)
          } *> IO.println(s"Symlinks created in: $dir")
        case None => IO.unit
    yield ()

  private def findVideoFilePath(id: String): Option[String] =
    val dir = os.pwd / "videos"
    if os.exists(dir) then
      os.list(dir).find(_.last.startsWith(s"$id.")).map(_.toString)
    else None

  // -- list ------------------------------------------------------------------

  private val listStatus = Opts.option[String]("status", "Filter by status").orNone
  private val listShow = Opts.option[String]("show", "Filter by show name").orNone

  val listOpts: Opts[(Option[String], Option[String])] = (listStatus, listShow).tupled

  def listRun(ds: DataSource, status: Option[String], show: Option[String]): IO[ExitCode] =
    for
      videos <- Db.connectIO(ds)(VideoRepo.listByFilters(status, show))
      _      <- IO.println(Output.table(videos))
    yield ExitCode.Success

  // -- retire ----------------------------------------------------------------

  private val retireId = Opts.argument[String](metavar = "id")
  private val retireReason = Opts.option[String]("reason", "Reason for retiring", "r")

  val retireOpts: Opts[(String, String)] = (retireId, retireReason).tupled

  def retireRun(ds: DataSource, id: String, reason: String): IO[ExitCode] =
    Db.connectIO(ds)(VideoRepo.findById(id)).flatMap:
      case None =>
        IO.println(s"Video not found: $id") *> IO.pure(ExitCode(1))
      case Some(v) =>
        v.status match
          case approved: VideoStatus.Approved =>
            for
              now <- IO.realTimeInstant
              newStatus = approved.retire(now, reason)
              _ <- Db.transactIO(ds)(VideoRepo.updateStatus(id, newStatus, now))
              _ <- IO.println(s"Retired: $id ($reason)")
            yield ExitCode.Success
          case other =>
            IO.println(s"Cannot retire: $id is ${Output.statusLabel(other)}, not Approved") *>
            IO.pure(ExitCode(1))
