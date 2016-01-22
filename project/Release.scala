import sbt._
import Keys._
import StatusPlugin.autoImport._
import org.apache.ivy.util.url.CredentialsStore

object Release extends Build {
  lazy val remoteBase = SettingKey[String]("remote-base")
  lazy val remoteID = SettingKey[String]("remote-id")
  lazy val launcherRemotePath = SettingKey[String]("launcher-remote-path")
  lazy val deployLauncher = TaskKey[Unit]("deploy-launcher", "Upload the launcher to its traditional location for compatibility with existing scripts.")
  lazy val checkCredentials = TaskKey[Unit]("checkCredentials", "Checks to ensure credentials for this user exists.")

  val PublishRepoHost = "private-repo.typesafe.com"

  def launcherSettings(launcher: TaskKey[File]): Seq[Setting[_]] = Seq(
    launcherRemotePath <<= (organization, version, moduleName) { (org, v, n) => List(org, n, v, n + ".jar").mkString("/") },
    deployLauncher <<= deployLauncher(launcher)
  )

  // Add credentials if they exist.
  def lameCredentialSettings: Seq[Setting[_]] =
    if (CredentialsFile.exists) Seq(credentials in ThisBuild += Credentials(CredentialsFile))
    else Nil
  def releaseSettings: Seq[Setting[_]] = Seq(
    publishTo in ThisBuild <<= publishResolver,
    remoteID in ThisBuild <<= publishStatus("typesafe-ivy-" + _),
    remoteBase in ThisBuild <<= publishStatus("https://" + PublishRepoHost + "/typesafe/ivy-" + _),
    checkCredentials := {
      // Note - This will eitehr issue a failure or succeed.
      getCredentials(credentials.value, streams.value.log)
    }
  ) ++ lameCredentialSettings

  def snapshotPattern(version: String) = Resolver.localBasePattern.replaceAll("""\[revision\]""", version)
  def publishResolver: Def.Initialize[Option[Resolver]] = (remoteID, remoteBase) { (id, base) =>
    Some(Resolver.url("publish-" + id, url(base))(Resolver.ivyStylePatterns))
  }

  lazy val CredentialsFile: File = Path.userHome / ".ivy2" / ".typesafe-credentials"

  // this is no longer strictly necessary, since the launcher is now published as normal
  // however, existing scripts expect the launcher to be in a certain place and normal publishing adds "jars/"
  // to the published path
  def deployLauncher(launcher: TaskKey[File]) =
    (launcher, launcherRemotePath, credentials, remoteBase, streams) map { (launchJar, remotePath, creds, base, s) =>
      val (uname, pwd) = getCredentials(creds, s.log)
      val request = dispatch.classic.url(base) / remotePath <<< (launchJar, "binary/octet-stream") as (uname, pwd)
      val http = new dispatch.classic.Http
      try { http(request.as_str) } finally { http.shutdown() }
      ()
    }
  def getCredentials(cs: Seq[Credentials], log: Logger): (String, String) =
    {
      Credentials.forHost(cs, PublishRepoHost) match {
        case Some(creds) => (creds.userName, creds.passwd)
        case None        => sys.error("No credentials defined for " + PublishRepoHost)
      }
    }
}
