package io.heterogeneousmicroservices.helidonservice

import com.orbitz.consul.Consul
import com.orbitz.consul.model.agent.ImmutableRegistration
import io.helidon.common.http.Http
import io.helidon.common.http.MediaType
import io.helidon.config.Config
import io.helidon.media.jackson.server.JacksonSupport
import io.helidon.webserver.Handler
import io.helidon.webserver.NotFoundException
import io.helidon.webserver.Routing
import io.helidon.webserver.ServerConfiguration
import io.helidon.webserver.WebServer
import io.heterogeneousmicroservices.helidonservice.config.ApplicationInfoProperties
import io.heterogeneousmicroservices.helidonservice.model.Projection
import io.heterogeneousmicroservices.helidonservice.service.ApplicationInfoService
import io.heterogeneousmicroservices.helidonservice.service.KtorServiceClient
import org.koin.dsl.module.module
import org.koin.standalone.KoinComponent
import org.koin.standalone.StandAloneContext.startKoin
import org.koin.standalone.inject
import org.slf4j.LoggerFactory

// todo rename in two projects
val applicationContext = module {
    single { ApplicationInfoService(get(), get()) }
    single { ApplicationInfoProperties() }
    single { KtorServiceClient(get()) }
    single { Consul.builder().withUrl("http://localhost:8500").build() }
}

private val log = LoggerFactory.getLogger(HelidonServiceApplication::class.java)

object HelidonServiceApplication : KoinComponent {

    @JvmStatic
    fun main(args: Array<String>) {
        startKoin(listOf(applicationContext))
        startServer()
    }

    fun startServer(): WebServer {
        val applicationInfoService: ApplicationInfoService by inject()
        val consulClient: Consul by inject()
        val applicationInfoProperties: ApplicationInfoProperties by inject()
        val serviceName = applicationInfoProperties.name

        return startServer(applicationInfoService, consulClient, serviceName)
    }
}

private fun startServer(
    applicationInfoService: ApplicationInfoService,
    consulClient: Consul,
    serviceName: String
): WebServer {
    val serverConfig = ServerConfiguration.create(Config.create().get("webserver"))

    val server: WebServer = WebServer
        .builder(createRouting(applicationInfoService))
        .config(serverConfig)
        .build()

    server.start().thenAccept { ws ->
        log.info("Service running at: http://localhost:" + ws.port())
        // register in Consul
        consulClient.agentClient().register(createConsulRegistration(serviceName, ws.port()))
    }

    return server
}

private fun createRouting(applicationInfoService: ApplicationInfoService) = Routing.builder()
    .register(JacksonSupport.create())
    .get("/application-info", Handler { req, res ->
        val projection = req.queryParams()
            .first("projection")
            .map { Projection.valueOf(it.toUpperCase()) }
            .orElse(Projection.DEFAULT)

        res
            .status(Http.ResponseStatus.create(200))
            .send(applicationInfoService.get(projection))
    })
    .get("/application-info/logo", Handler { req, res ->
        res.headers().contentType(MediaType.create("image", "png"))
        res
            .status(Http.ResponseStatus.create(200))
            .send(applicationInfoService.getLogo())
    })
    .error(NotFoundException::class.java) { req, res, ex ->
        log.error("NotFoundException:", ex)
        res.status(Http.Status.BAD_REQUEST_400).send()
    }
    .error(Exception::class.java) { req, res, ex ->
        log.error("Exception:", ex)
        res.status(Http.Status.INTERNAL_SERVER_ERROR_500).send()
    }
    .build()

private fun createConsulRegistration(serviceName: String, port: Int) = ImmutableRegistration.builder()
    .id("$serviceName-$port")
    .name(serviceName)
    .address("localhost")
    .port(port)
    .build()