package com.local.voicehall.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main() {
    embeddedServer(Netty, port = (System.getenv("PORT") ?: "8080").toInt()) {
        module()
    }.start(wait = true)
}

fun Application.module(
    env: Map<String, String> = System.getenv(),
) {
    if (env["APP_ENV"].equals("production", ignoreCase = true)) {
        require(!env["JWT_SECRET"].isNullOrBlank()) { "生产环境必须配置 JWT_SECRET" }
        require(!env["DEVICE_MASTER_KEY"].isNullOrBlank()) {
            "生产环境必须配置 DEVICE_MASTER_KEY"
        }
        require(!env["BOOTSTRAP_ADMIN_PASSWORD"].isNullOrBlank()) {
            "生产环境必须配置 BOOTSTRAP_ADMIN_PASSWORD"
        }
    }
    val jsonCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    val dataSource = DatabaseFactory.create(env)
    val jwt = JwtService(env["JWT_SECRET"] ?: "development-jwt-secret-change-before-production")
    val deviceCrypto = DeviceCrypto(
        env["DEVICE_MASTER_KEY"] ?: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    )
    val repository = OpsRepository(dataSource, deviceCrypto)
    val appLog = environment.log
    repository.bootstrap(
        organizationName = env["BOOTSTRAP_ORG"] ?: "默认语音厅组织",
        username = env["BOOTSTRAP_ADMIN_USER"] ?: "admin",
        password = env["BOOTSTRAP_ADMIN_PASSWORD"] ?: "ChangeMe-12345",
        displayName = env["BOOTSTRAP_ADMIN_NAME"] ?: "系统所有者",
    )

    install(ContentNegotiation) { json(jsonCodec) }
    install(CallLogging) { level = Level.INFO }
    install(WebSockets) {
        pingPeriodMillis = 20_000
        timeoutMillis = 30_000
        maxFrameSize = 1_048_576
        masking = false
    }
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Device-Id")
        allowHeader("X-Timestamp")
        allowHeader("X-Signature")
        allowHeader("X-Client-Operation-Id")
        allowCredentials = false
        env["CORS_HOSTS"]
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.forEach { allowHost(it, schemes = listOf("https", "http")) }
    }
    install(StatusPages) {
        exception<AuthorizationException> { call, error ->
            call.respond(HttpStatusCode.Forbidden, ApiError(error.message ?: "权限不足"))
        }
        exception<IllegalArgumentException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ApiError(error.message ?: "请求参数无效"))
        }
        exception<IllegalStateException> { call, error ->
            call.respond(HttpStatusCode.Conflict, ApiError(error.message ?: "当前状态不允许该操作"))
        }
        exception<Throwable> { call, error ->
            appLog.error("Unhandled request failure", error)
            call.respond(HttpStatusCode.InternalServerError, ApiError("服务器内部错误"))
        }
    }
    authentication {
        jwt("auth-jwt") {
            realm = jwt.realm
            verifier(jwt.verifier())
            validate { credential ->
                val subject = credential.payload.subject
                val org = credential.payload.getClaim("org").asString()
                val role = credential.payload.getClaim("role").asString()
                val tokenVersion = credential.payload.getClaim("ver").asInt() ?: 0
                if (
                    subject.isNullOrBlank() || org.isNullOrBlank() || role.isNullOrBlank() ||
                    !repository.isAccessTokenValid(subject, org, tokenVersion)
                ) null else JWTPrincipal(credential.payload)
            }
        }
    }

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "voice-hall-ops-api", "version" to "1.0.0"))
        }
        route("/auth") {
            post("/login") {
                val request = call.receive<LoginRequest>()
                if (!repository.isLoginAllowed(request.username)) {
                    call.respond(HttpStatusCode.TooManyRequests, ApiError("Too many login attempts; retry later"))
                    return@post
                }
                val account = repository.findAccount(request.username)
                if (account == null || !account.enabled ||
                    !Passwords.verify(account.passwordHash, request.password.toCharArray()) ||
                    (account.totpSecret != null && !Totp.verify(account.totpSecret, request.otp))
                ) {
                    repository.recordLoginFailure(request.username)
                    call.respond(HttpStatusCode.Unauthorized, ApiError("用户名、密码或动态验证码错误"))
                    return@post
                }
                repository.clearLoginFailures(request.username)
                val grant = repository.issueRefreshToken(account)
                call.respond(
                    LoginResponse(
                        jwt.token(account),
                        900,
                        grant.refreshToken,
                        grant.refreshExpiresInSeconds,
                        AccountView(
                            account.id,
                            account.organizationId,
                            account.username,
                            account.displayName,
                            account.role,
                        ),
                        Roles.permissionsFor(account.role),
                    ),
                )
            }
            post("/refresh") {
                val request = call.receive<RefreshRequest>()
                val grant = runCatching { repository.rotateRefreshToken(request.refreshToken) }
                    .getOrElse {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("Refresh token is invalid or expired"))
                        return@post
                    }
                call.respond(
                    LoginResponse(
                        accessToken = jwt.token(grant.account),
                        expiresInSeconds = 900,
                        refreshToken = grant.refreshToken,
                        refreshExpiresInSeconds = grant.refreshExpiresInSeconds,
                        account = AccountView(
                            grant.account.id,
                            grant.account.organizationId,
                            grant.account.username,
                            grant.account.displayName,
                            grant.account.role,
                        ),
                        permissions = Roles.permissionsFor(grant.account.role),
                    ),
                )
            }
            post("/logout") {
                repository.revokeRefreshToken(call.receive<LogoutRequest>().refreshToken)
                call.respond(mapOf("status" to "logged_out"))
            }
        }

        post("/devices/events") {
            val deviceId = call.request.headers["X-Device-Id"].orEmpty()
            val timestamp = call.request.headers["X-Timestamp"].orEmpty()
            val signature = call.request.headers["X-Signature"].orEmpty()
            val rawBody = call.receiveText()
            val event = jsonCodec.decodeFromString<DeviceEventInput>(rawBody)
            val result = repository.verifyAndStoreDeviceEvent(
                deviceId,
                timestamp,
                signature,
                rawBody,
                event,
            )
            call.respond(
                if (result.inserted) HttpStatusCode.Accepted else HttpStatusCode.OK,
                DeviceAck(event.eventId, result.accepted),
            )
        }

        webSocket("/devices/ws") {
            val deviceId = call.request.queryParameters["deviceId"].orEmpty()
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val envelope = jsonCodec.decodeFromString<DeviceWsEnvelope>(frame.readText())
                val body = jsonCodec.encodeToString(envelope.event)
                val result = repository.verifyAndStoreDeviceEvent(
                    deviceId,
                    envelope.timestamp,
                    envelope.signature,
                    body,
                    envelope.event,
                )
                send(jsonCodec.encodeToString(DeviceAck(envelope.event.eventId, result.accepted)))
            }
        }

        authenticate("auth-jwt") {
            get("/auth/me") {
                call.respond(repository.getAccount(call.identity()))
            }
            post("/auth/change-password") {
                val request = call.receive<ChangePasswordRequest>()
                repository.changePassword(call.identity(), request.currentPassword, request.newPassword)
                call.respond(ChangePasswordResponse())
            }
            route("/accounts") {
                get {
                    call.requirePermission("rooms.write")
                    call.respond(repository.listAccounts(call.identity()))
                }
                post {
                    call.requirePermission("rooms.write")
                    call.respond(HttpStatusCode.Created, repository.createAccount(call.identity(), call.receive()))
                }
            }
            route("/rooms") {
                get {
                    call.requirePermission("rooms.read")
                    call.respond(repository.listRooms(call.identity()))
                }
                post {
                    call.requirePermission("rooms.write")
                    call.respond(HttpStatusCode.Created, repository.createRoom(call.identity(), call.receive()))
                }
            }
            route("/shifts") {
                get {
                    call.requirePermission("rooms.read")
                    call.respond(repository.listShifts(call.identity(), call.request.queryParameters["roomId"]))
                }
                post {
                    call.requirePermission("shifts.write")
                    call.respond(HttpStatusCode.Created, repository.createShift(call.identity(), call.receive()))
                }
            }
            get("/mic-segments") {
                call.requirePermission("mic.read")
                call.respond(
                    repository.listMicSegments(
                        call.identity(),
                        call.request.queryParameters["roomId"],
                        call.request.queryParameters["start"]?.toLongOrNull(),
                        call.request.queryParameters["end"]?.toLongOrNull(),
                    ),
                )
            }
            get("/queue-entries") {
                call.requirePermission("mic.read")
                call.respond(
                    repository.listQueueEntries(
                        call.identity(),
                        call.request.queryParameters["roomId"],
                        call.request.queryParameters["shiftId"],
                    ),
                )
            }
            get("/bindings") {
                call.requirePermission("mic.read")
                call.respond(repository.listBindings(call.identity(), call.request.queryParameters["roomId"]))
            }
            get("/attendance") {
                call.requirePermission("mic.read")
                call.respond(
                    repository.listAttendance(
                        call.identity(),
                        call.request.queryParameters["roomId"],
                        call.request.queryParameters["shiftId"],
                    ),
                )
            }
            route("/customers") {
                get {
                    call.requirePermission("customers.read")
                    call.respond(repository.listCustomers(call.identity()).map { customer ->
                        if (Roles.allows(call.identity().role, "amounts.exact")) customer
                        else customer.copy(
                            revenue7dCents = 0,
                            revenue30dCents = 0,
                            revenue90dCents = 0,
                            lifetimeRevenueCents = 0,
                        )
                    })
                }
                post {
                    call.requirePermission("customers.write")
                    call.respond(HttpStatusCode.Created, repository.createCustomer(call.identity(), call.receive()))
                }
            }
            route("/tasks") {
                get {
                    call.requirePermission("tasks.read")
                    call.respond(repository.listTasks(call.identity(), call.request.queryParameters["state"]))
                }
                post {
                    call.requirePermission("tasks.write")
                    call.respond(HttpStatusCode.Created, repository.createTask(call.identity(), call.receive()))
                }
                post("/{id}/claim") {
                    call.requirePermission("tasks.claim")
                    call.respond(
                        repository.transitionTask(
                            call.identity(),
                            call.requiredId(),
                            "claim",
                            expectedVersion = call.request.queryParameters["expectedVersion"]?.toIntOrNull(),
                            clientOperationId = call.request.headers["X-Client-Operation-Id"],
                        ),
                    )
                }
                post("/{id}/start") {
                    call.requirePermission("tasks.execute")
                    call.respond(
                        repository.transitionTask(
                            call.identity(),
                            call.requiredId(),
                            "start",
                            expectedVersion = call.request.queryParameters["expectedVersion"]?.toIntOrNull(),
                            clientOperationId = call.request.headers["X-Client-Operation-Id"],
                        ),
                    )
                }
                post("/{id}/submit") {
                    call.requirePermission("tasks.execute")
                    call.respond(
                        repository.transitionTask(
                            call.identity(),
                            call.requiredId(),
                            "submit",
                            call.receive<TaskResultInput>(),
                        ),
                    )
                }
                post("/{id}/approve") {
                    call.requirePermission("tasks.review")
                    call.respond(repository.transitionTask(call.identity(), call.requiredId(), "approve"))
                }
                post("/{id}/reject") {
                    call.requirePermission("tasks.review")
                    call.respond(repository.transitionTask(call.identity(), call.requiredId(), "reject"))
                }
                post("/{id}/cancel") {
                    call.requirePermission("tasks.write")
                    call.respond(repository.transitionTask(call.identity(), call.requiredId(), "cancel"))
                }
            }
            route("/revenue-imports") {
                post("/preview") {
                    call.requirePermission("revenue.write")
                    call.respond(repository.previewRevenue(call.identity(), call.receive()))
                }
                post("/{id}/commit") {
                    call.requirePermission("revenue.write")
                    call.respond(repository.commitRevenue(call.identity(), call.requiredId()))
                }
            }
            route("/settlements") {
                post("/rules") {
                    call.requirePermission("settlements.write")
                    call.respond(mapOf("id" to repository.createSettlementRule(call.identity(), call.receive())))
                }
                post("/expenses") {
                    call.requirePermission("settlements.write")
                    call.respond(mapOf("id" to repository.addExpense(call.identity(), call.receive())))
                }
                post("/preview") {
                    call.requirePermission("finance.read")
                    call.respond(repository.previewSettlement(call.identity(), call.receive()))
                }
                post("/close") {
                    call.requirePermission("settlements.write")
                    call.respond(repository.closeSettlement(call.identity(), call.receive()))
                }
                post("/{id}/adjustments") {
                    call.requirePermission("settlements.write")
                    call.respond(mapOf("id" to repository.addAdjustment(call.identity(), call.requiredId(), call.receive())))
                }
            }
            get("/reports/summary") {
                call.requirePermission("reports.read")
                val end = call.request.queryParameters["end"]?.toLongOrNull() ?: System.currentTimeMillis()
                val start = call.request.queryParameters["start"]?.toLongOrNull() ?: end - 30L * 86_400_000
                call.respond(repository.reportSummary(call.identity(), start, end))
            }
            route("/devices") {
                get {
                    call.requirePermission("devices.write")
                    call.respond(repository.listDevices(call.identity()))
                }
                post("/register") {
                    call.requirePermission("devices.write")
                    call.respond(HttpStatusCode.Created, repository.registerDevice(call.identity(), call.receive()))
                }
                post("/{id}/disable") {
                    call.requirePermission("devices.write")
                    call.respond(repository.disableDevice(call.identity(), call.requiredId()))
                }
            }
            get("/audit") {
                call.requirePermission("audit.read")
                call.respond(repository.listAudit(call.identity(), call.request.queryParameters["limit"]?.toIntOrNull() ?: 100))
            }
            post("/migration/legacy") {
                call.requirePermission("rooms.write")
                call.respond(mapOf("id" to repository.saveLegacyMigration(call.identity(), call.receive())))
            }
            post("/settings/value-levels") {
                call.requirePermission("rooms.write")
                repository.replaceValueLevels(call.identity(), call.receive())
                call.respond(mapOf("status" to "updated"))
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.identity(): RequestIdentity =
    principal<JWTPrincipal>()?.identity() ?: error("缺少登录身份")

private fun io.ktor.server.application.ApplicationCall.requirePermission(permission: String) {
    if (!Roles.allows(identity().role, permission)) {
        throw AuthorizationException("权限不足：$permission")
    }
}

private fun io.ktor.server.application.ApplicationCall.requiredId(): String =
    parameters["id"]?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("缺少资源 ID")

private class AuthorizationException(message: String) : RuntimeException(message)
