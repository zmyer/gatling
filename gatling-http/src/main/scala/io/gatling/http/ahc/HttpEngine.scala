/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.http.ahc

import java.util.{ ArrayList => JArrayList }
import java.util.concurrent.{ ExecutorService, TimeUnit, Executors, ThreadFactory }

import org.jboss.netty.channel.Channel
import org.jboss.netty.channel.socket.nio.{ NioWorkerPool, NioClientBossPool, NioClientSocketChannelFactory }
import org.jboss.netty.logging.{ InternalLoggerFactory, Slf4JLoggerFactory }
import org.jboss.netty.util.HashedWheelTimer

import com.ning.http.client.{ AsyncHttpClient, AsyncHttpClientConfig, Request }
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig.NettyWebSocketFactory
import com.ning.http.client.providers.netty.ws.NettyWebSocket
import com.ning.http.client.providers.netty.channel.pool.{ ChannelPool, DefaultChannelPool }
import com.ning.http.client.ws.{ WebSocketListener, WebSocketUpgradeHandler }
import com.typesafe.scalalogging.StrictLogging

import akka.actor.ActorRef
import io.gatling.core.ConfigKeys
import io.gatling.core.akka.AkkaDefaults
import io.gatling.core.check.CheckResult
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.controller.throttle.Throttler
import io.gatling.core.session.{ Session, SessionPrivateAttributes }
import io.gatling.http.action.sse.SseHandler
import io.gatling.http.action.ws.WsListener
import io.gatling.http.check.ws.WsCheck
import io.gatling.http.config.HttpProtocol
import io.gatling.http.request.HttpRequest
import io.gatling.http.response.ResponseBuilder
import io.gatling.http.util.SslHelper.{ RichAsyncHttpClientConfigBuilder, newKeyManagers, newTrustManagers }

object HttpTx {

  def silent(request: HttpRequest, primary: Boolean): Boolean = {

      def silentBecauseProtocolSilentResources = !primary && request.config.protocol.requestPart.silentResources

      def silentBecauseProtocolSilentURI: Option[Boolean] = request.config.protocol.requestPart.silentURI
        .map(_.matcher(request.ahcRequest.getUrl).matches)

    request.config.silent.orElse(silentBecauseProtocolSilentURI).getOrElse(silentBecauseProtocolSilentResources)
  }
}

case class HttpTx(session: Session,
                  request: HttpRequest,
                  responseBuilderFactory: Request => ResponseBuilder,
                  next: ActorRef,
                  primary: Boolean = true,
                  redirectCount: Int = 0,
                  update: Session => Session = Session.Identity) {

  val silent: Boolean = HttpTx.silent(request, primary)
}

case class SseTx(session: Session,
                 request: Request, // FIXME should it be a HttpRequest obj???
                 requestName: String,
                 protocol: HttpProtocol,
                 next: ActorRef,
                 start: Long,
                 reconnectCount: Int = 0,
                 check: Option[WsCheck] = None,
                 pendingCheckSuccesses: List[CheckResult] = Nil,
                 updates: List[Session => Session] = Nil) {

  def applyUpdates(session: Session) = {
    val newSession = session.update(updates)
    copy(session = newSession, updates = Nil)
  }
}

case class WsTx(session: Session,
                request: Request,
                requestName: String,
                protocol: HttpProtocol,
                next: ActorRef,
                start: Long,
                reconnectCount: Int = 0,
                check: Option[WsCheck] = None,
                pendingCheckSuccesses: List[CheckResult] = Nil,
                updates: List[Session => Session] = Nil) {

  def applyUpdates(session: Session) = {
    val newSession = session.update(updates)
    copy(session = newSession, updates = Nil)
  }
}

object HttpEngine {
  val AhcAttributeName = SessionPrivateAttributes.PrivateAttributePrefix + "http.ahc"
}

trait HttpEngine {

  def start(): Unit

  def stop(): Unit

  def newAhc(session: Session): AsyncHttpClient

  def newAhc(session: Option[Session]): AsyncHttpClient

  def defaultAhc: AsyncHttpClient

  def httpClient(session: Session, protocol: HttpProtocol): (Session, AsyncHttpClient)

  def startHttpTransaction(tx: HttpTx): Unit

  def startSseTransaction(tx: SseTx, sseActor: ActorRef): Unit

  def startWebSocketTransaction(tx: WsTx, wsActor: ActorRef): Unit
}

class AhcHttpEngine(implicit configuration: GatlingConfiguration) extends HttpEngine with AkkaDefaults with StrictLogging {

  // set up Netty LoggerFactory for slf4j instead of default JDK
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  lazy val state = loadInternalState()
  lazy val defaultAhc = newAhc(None)

  def start(): Unit = {
    system.registerOnTermination(stop())
    defaultAhc
  }

  def stop(): Unit = {
    state.applicationThreadPool.shutdown()
    state.nioThreadPool.shutdown()
  }

  def newAhc(session: Session): AsyncHttpClient = newAhc(Some(session))

  def newAhc(session: Option[Session]) = {
    val ahcConfig = session.flatMap { session =>

      val trustManagers = for {
        file <- session(ConfigKeys.http.ssl.trustStore.File).asOption[String]
        password <- session(ConfigKeys.http.ssl.trustStore.Password).asOption[String]
        storeType = session(ConfigKeys.http.ssl.trustStore.Type).asOption[String]
        algorithm = session(ConfigKeys.http.ssl.trustStore.Algorithm).asOption[String]
      } yield newTrustManagers(storeType, file, password, algorithm)

      val keyManagers = for {
        file <- session(ConfigKeys.http.ssl.keyStore.File).asOption[String]
        password <- session(ConfigKeys.http.ssl.keyStore.Password).asOption[String]
        storeType = session(ConfigKeys.http.ssl.keyStore.Type).asOption[String]
        algorithm = session(ConfigKeys.http.ssl.keyStore.Algorithm).asOption[String]
      } yield newKeyManagers(storeType, file, password, algorithm)

      trustManagers.orElse(keyManagers).map { _ =>
        logger.info(s"Setting a custom SSLContext for user ${session.userId}")
        new AsyncHttpClientConfig.Builder(state.defaultAhcConfig).setSSLContext(trustManagers, keyManagers).build
      }

    }.getOrElse(state.defaultAhcConfig)

    val client = new AsyncHttpClient(ahcConfig)
    system.registerOnTermination(client.close())
    client
  }

  def httpClient(session: Session, protocol: HttpProtocol): (Session, AsyncHttpClient) = {
    if (protocol.enginePart.shareClient)
      (session, defaultAhc)
    else
      session(HttpEngine.AhcAttributeName).asOption[AsyncHttpClient] match {
        case Some(client) => (session, client)
        case _ =>
          val httpClient = newAhc(session)
          (session.set(HttpEngine.AhcAttributeName, httpClient), httpClient)
      }
  }

  def startHttpTransaction(tx: HttpTx): Unit = {

    logger.info(s"Sending request=${tx.request.requestName} uri=${tx.request.ahcRequest.getUri}: scenario=${tx.session.scenarioName}, userId=${tx.session.userId}")

    val requestConfig = tx.request.config

    val (newTx, client) = {
      val (newSession, client) = httpClient(tx.session, requestConfig.protocol)
      (tx.copy(session = newSession), client)
    }

    val ahcRequest = newTx.request.ahcRequest
    val handler = new AsyncHandler(newTx)

    if (requestConfig.throttled)
      Throttler.throttle(tx.session.scenarioName, () => client.executeRequest(ahcRequest, handler))
    else
      client.executeRequest(ahcRequest, handler)
  }

  def startSseTransaction(tx: SseTx, sseActor: ActorRef): Unit = {
    val (newTx, client) = {
      val (newSession, client) = httpClient(tx.session, tx.protocol)
      (tx.copy(session = newSession), client)
    }

    val handler = new SseHandler(newTx, sseActor)
    client.executeRequest(newTx.request, handler)
  }

  def startWebSocketTransaction(tx: WsTx, wsActor: ActorRef): Unit = {
    val (newTx, client) = {
      val (newSession, client) = httpClient(tx.session, tx.protocol)
      (tx.copy(session = newSession), client)
    }

    val listener = new WsListener(newTx, wsActor)

    val handler = new WebSocketUpgradeHandler.Builder().addWebSocketListener(listener).build
    client.executeRequest(tx.request, handler)
  }

  def loadInternalState() = {

    import configuration.http. { ahc => ahcConfig }

    val applicationThreadPool = Executors.newCachedThreadPool(new ThreadFactory {
      override def newThread(r: Runnable) = {
        val t = new Thread(r, "Netty Thread")
        t.setDaemon(true)
        t
      }
    })

    val nioThreadPool = Executors.newCachedThreadPool

    val nettyTimer = {
      val timer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS)
      timer.start()
      system.registerOnTermination(timer.stop())
      timer
    }

    val channelPool = new DefaultChannelPool(ahcConfig.pooledConnectionIdleTimeout,
      ahcConfig.connectionTTL,
      ahcConfig.allowPoolingSslConnections,
      nettyTimer)

    val nettyConfig = {
      val numWorkers = ahcConfig.ioThreadMultiplier * Runtime.getRuntime.availableProcessors
      val socketChannelFactory = new NioClientSocketChannelFactory(new NioClientBossPool(nioThreadPool, 1, nettyTimer, null), new NioWorkerPool(nioThreadPool, numWorkers))
      system.registerOnTermination(socketChannelFactory.releaseExternalResources())
      val nettyConfig = new NettyAsyncHttpProviderConfig
      nettyConfig.setSocketChannelFactory(socketChannelFactory)
      nettyConfig.setNettyTimer(nettyTimer)
      nettyConfig.setChannelPool(channelPool)
      nettyConfig.setHttpClientCodecMaxInitialLineLength(ahcConfig.httpClientCodecMaxInitialLineLength)
      nettyConfig.setHttpClientCodecMaxHeaderSize(ahcConfig.httpClientCodecMaxHeaderSize)
      nettyConfig.setHttpClientCodecMaxChunkSize(ahcConfig.httpClientCodecMaxChunkSize)
      nettyConfig.setNettyWebSocketFactory(new NettyWebSocketFactory {
        override def newNettyWebSocket(channel: Channel, nettyConfig: NettyAsyncHttpProviderConfig): NettyWebSocket =
          new NettyWebSocket(channel, nettyConfig, new JArrayList[WebSocketListener](1))
      })
      nettyConfig.setKeepEncodingHeader(ahcConfig.keepEncodingHeader)
      nettyConfig.setWebSocketMaxFrameSize(ahcConfig.webSocketMaxFrameSize)
      nettyConfig
    }

    val defaultAhcConfig = {
      val ahcConfigBuilder = new AsyncHttpClientConfig.Builder()
        .setAllowPoolingConnections(ahcConfig.allowPoolingConnections)
        .setAllowPoolingSslConnections(ahcConfig.allowPoolingSslConnections)
        .setCompressionEnforced(ahcConfig.compressionEnforced)
        .setConnectTimeout(ahcConfig.connectTimeout)
        .setPooledConnectionIdleTimeout(ahcConfig.pooledConnectionIdleTimeout)
        .setReadTimeout(ahcConfig.readTimeout)
        .setConnectionTTL(ahcConfig.connectionTTL)
        .setIOThreadMultiplier(ahcConfig.ioThreadMultiplier)
        .setMaxConnectionsPerHost(ahcConfig.maxConnectionsPerHost)
        .setMaxConnections(ahcConfig.maxConnections)
        .setMaxRequestRetry(ahcConfig.maxRetry)
        .setRequestTimeout(ahcConfig.requestTimeOut)
        .setUseProxyProperties(ahcConfig.useProxyProperties)
        .setUserAgent(null)
        .setExecutorService(applicationThreadPool)
        .setAsyncHttpClientProviderConfig(nettyConfig)
        .setWebSocketTimeout(ahcConfig.webSocketTimeout)
        .setUseRelativeURIsWithConnectProxies(ahcConfig.useRelativeURIsWithConnectProxies)
        .setAcceptAnyCertificate(ahcConfig.acceptAnyCertificate)
        .setEnabledProtocols(ahcConfig.httpsEnabledProtocols match {
          case Nil => null
          case ps  => ps.toArray
        })
        .setEnabledCipherSuites(ahcConfig.httpsEnabledCipherSuites match {
          case Nil => null
          case ps  => ps.toArray
        })

      val trustManagers = configuration.http.ssl.trustStore
        .map(config => newTrustManagers(config.storeType, config.file, config.password, config.algorithm))

      val keyManagers = configuration.http.ssl.keyStore
        .map(config => newKeyManagers(config.storeType, config.file, config.password, config.algorithm))

      if (trustManagers.isDefined || keyManagers.isDefined)
        ahcConfigBuilder.setSSLContext(trustManagers, keyManagers)

      ahcConfigBuilder.build
    }

    new InternalState(applicationThreadPool, nioThreadPool, channelPool, nettyConfig, defaultAhcConfig)
  }

  case class InternalState(applicationThreadPool: ExecutorService,
                           nioThreadPool: ExecutorService,
                           channelPool: ChannelPool,
                           nettyConfig: NettyAsyncHttpProviderConfig,
                           defaultAhcConfig: AsyncHttpClientConfig)
}

