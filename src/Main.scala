package kidstv

import cats.effect.{ExitCode, IO}
import com.monovore.decline.*
import com.monovore.decline.effect.*
import kidstv.cli.Commands
import kidstv.storage.Db
import javax.sql.DataSource

object Main extends CommandIOApp(
  name = "kidstv",
  header = "Curated kids TV library manager"
):

  private val dbPath = sys.env.getOrElse("KIDSTV_DB", "library.db")

  override def main: Opts[IO[ExitCode]] =
    val add = Opts.subcommand("add", "Add a video URL to the library")(
      Commands.addOpts.map(url => (ds: DataSource) => Commands.addRun(ds, url))
    )
    val enrich = Opts.subcommand("enrich", "Enrich videos with metadata and tags")(
      Commands.enrichOpts.map((id, all) => (ds: DataSource) => Commands.enrichRun(ds, id, all))
    )
    val review = Opts.subcommand("review", "Interactively review candidate videos")(
      Commands.reviewOpts.map(_ => (ds: DataSource) => Commands.reviewRun(ds))
    )
    val sync = Opts.subcommand("sync", "Sync downloaded files with library state")(
      Commands.syncOpts.map(_ => (ds: DataSource) => Commands.syncRun(ds))
    )
    val buildSession = Opts.subcommand("build-session", "Build a viewing session playlist")(
      Commands.buildSessionOpts.map((d, m, o) => (ds: DataSource) => Commands.buildSessionRun(ds, d, m, o))
    )
    val list = Opts.subcommand("list", "List videos in the library")(
      Commands.listOpts.map((s, sh) => (ds: DataSource) => Commands.listRun(ds, s, sh))
    )
    val retire = Opts.subcommand("retire", "Retire an approved video")(
      Commands.retireOpts.map((id, r) => (ds: DataSource) => Commands.retireRun(ds, id, r))
    )

    val command = add orElse enrich orElse review orElse sync orElse buildSession orElse list orElse retire

    command.map: action =>
      Db.withDb(dbPath)(action)
