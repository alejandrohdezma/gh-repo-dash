#!/usr/bin/env -S scala-cli shebang

//> using scala 3.4.0
//> using option -Wunused:all
//> using dep "com.alejandrohdezma::alfred4s:2.1.0"

import java.time.temporal.ChronoUnit

import java.time.Instant

import os.Path

import os.CommandResult

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

import cats.syntax.all.*
import mouse.all.*

import sttp.client4.quick.*

import ujson.Value

import alfred4s.syntax.*
import alfred4s.models.*

///////////////////
// Configuration //
///////////////////

object config:

  val GITHUB_TOKEN = sys.env.get("GITHUB_TOKEN").filter(_.nonEmpty)

  val organizations = sys
    .env("organizations")
    .split(',')
    .map(_.split('/'))
    .map { case Array(name, topic) => Organization(name, topic) }
    .toList

final case class Organization(name: String, topic: String)

final case class GitHubToken(value: String)

///////////////
// Utilities //
///////////////

extension (sc: StringContext)

  /** Runs the command represented by the interpolated string using `os.proc`.
    *
    * The command is configured so the `PATH` environment variable also contains the ones defined under `EXTRA_PATH`
    * from the workflow's configuration variables.
    */
  def $(args: Any*): CommandResult =
    alfred4s.tokenize(sc.s(args*)) |> (os.proc(_).call())

////////////
// Givens //
////////////

/** Returns the GitHub token from either an environment variable the user sets when configuring the workflow or
  * alternatively from the system's keychain store under the name `GitHub Token`.
  */
given GitHubToken = GitHubToken {
  config.GITHUB_TOKEN.getOrElse {
    Try($"security find-generic-password -s 'GitHub Token' -w")
      .map(_.out.text().trim())
      .filter(_ != "")
      .getOrElse(
        throw fail("You must set a GitHub Token", "Open workflow configuration and ensure one is set")
      )
  }
}

////////
// gh //
////////

def commitsBetween(org: String, repo: String, tag: String, defaultBranch: String)(using token: GitHubToken): Int =
  val response = githubRequest
    .get(uri"https://api.github.com/repos/$org/$repo/compare/$tag...$defaultBranch")
    .send()

  val json = ujson.read(response.body)

  json("commits").arr
    .filterNot(_("author").obj("login").str == "github-actions[bot]")
    .length

/** Returns the list of repositories of a specific organization.
  *
  * The `page` argument can be used to control the page to retrieve.
  *
  * When requesting the first page the method will recursively retrieve repositories from all of the available pages.
  */
def repositories(org: String, topic: String, page: Int = 1)(using token: GitHubToken): List[Item] =
  val perPage = 100 // max is 100

  val response = githubRequest
    .get(uri"https://api.github.com/search/repositories?q=archived:false org:$org $topic&per_page=$perPage&page=$page")
    .send()

  val json = ujson.read(response.body)

  val totalPages = math.ceil(json("total_count").num / perPage).toInt

  val thisPage = json("items").arr.toList.traverse { repo =>
    val name = repo("name").str

    Future {
      alfred4s.cached(s"$org-$name", 1.hour) {
        alfred4s.log(s"Fetching repository $org/$name")

        val defaultBranch = repo("default_branch").str

        val response = githubRequest
          .get(uri"https://api.github.com/repos/$org/$name/releases/latest")
          .send()

        val (subtitle, latestTag, commits) =
          Option
            .when(response.code.isSuccess) {
              val json = ujson.read(response.body)

              val tagName = json("tag_name").strOpt

              val publishedAt = json("published_at").strOpt.map(Instant.parse(_))

              val commits = tagName.map(commitsBetween(org, name, _, defaultBranch))

              (tagName, publishedAt, commits).mapN { (tagName, publishedAt, commits) =>
                val days   = publishedAt.until(Instant.now(), ChronoUnit.DAYS)
                val months = days / 30

                val published =
                  if publishedAt.isBefore(Instant.now().minus(2, ChronoUnit.YEARS)) then "more than two years ago"
                  else if publishedAt.isBefore(Instant.now().minus(1, ChronoUnit.YEARS)) then "more than a year ago"
                  else if months == 1 then "last month"
                  else if months > 0 then s"$months months ago"
                  else if days == 1 then "yesterday"
                  else if days > 1 then s"$days days ago"
                  else "today"

                val subtitle =
                  s"Latest release: $tagName (published $published) · $commits new commits til $defaultBranch"

                (subtitle, tagName, commits)
              }
            }
            .flatten
            .getOrElse(("No releases found", defaultBranch, 0))

        item(name)
          .subtitle(subtitle)
          .variable("repository", name)
          .variable("default_branch", defaultBranch)
          .variable("tag", latestTag)
          .variable("commits", commits.show)
          .variable("owner", org)
      }
    }
  }

  val nextPages: Future[List[Item]] =
    if totalPages == 1 || page != 1 then Future.successful(Nil)
    else
      (2 to totalPages).toList
        .flatTraverse(page => Future(repositories(org, topic, page)))

  List(thisPage, nextPages).flatSequence |> (Await.result(_, 50.seconds))

def githubRequest(using token: GitHubToken) =
  quickRequest.auth
    .bearer(token.value)
    .header("Accept", "application/vnd.github+json")
    .header("X-GitHub-Api-Version", "2022-11-28")

def storeAvatarInCacheOrRestore(name: String)(url: String): Path =
  val path = alfred4s.workflow.cache / "icons" / s"$name.png"

  os.makeDir.all(alfred4s.workflow.cache / "icons")

  if !os.exists(path) then
    val response = quickRequest.get(uri"$url").response(asByteArrayAlways).send()

    os.write(path, response.body)

  path

def userAvatarUrl(org: Organization)(using token: GitHubToken): Option[String] =
  alfred4s.log(s"Fetching user ${org.name}")

  val response = githubRequest
    .get(uri"https://api.github.com/users/${org.name}")
    .send()

  val json = ujson.read(response.body)

  json("avatar_url").strOpt

def organization(org: Organization)(using token: GitHubToken): Item =
  alfred4s.cached(org.name, 1.hour) {
    alfred4s.log(s"Fetching organization ${org.name}")

    val response = githubRequest
      .get(uri"https://api.github.com/orgs/${org.name}")
      .send()

    val avatar =
      Option
        .when(response.code.isClientError)(userAvatarUrl(org))
        .flatten
        .orElse(ujson.read(response.body)("avatar_url").strOpt)
        .map(storeAvatarInCacheOrRestore(org.name)(_).toString)
        .getOrElse("icons/github.png")

    item(org.name)
      .subtitle("↩ Use default topics · ⌘↩ Edit topics · ⇧ Force refresh")
      .variable("org", org.name)
      .variable("topic", org.topic)
      .icon(avatar)
  }

def organizations(): List[Item] =
  config.organizations.traverse(org => Future(organization(org)))
    |> (Await.result(_, 50.seconds))

//////////
// main //
//////////

val version = Try(alfred4s.workflow.version)

val main =
  if version.contains_("__VERSION__") || version.isFailure then alfred4s.app(args)
  else alfred4s.appWithNewVersionCheck(args, "permutive", "permutive-alfred-workflow", implicitly[GitHubToken].value)

main {
  case "clean" :: Nil =>
    alfred4s.cleanCache()
  case "clean" :: org :: repo :: Nil =>
    alfred4s.log(s"Cleaning cache for $org/$repo")
    alfred4s.cleanCache(s"$org-$repo")
  case "organization" :: org :: topic :: Nil =>
    items(repositories(org, topic).sortBy(_.variables("commits").get.toInt).reverse)
  case "organizations" :: Nil =>
    alfred4s.cached("organizations", 10.days)(items(organizations()))
}
