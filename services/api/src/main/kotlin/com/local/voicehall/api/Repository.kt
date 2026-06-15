package com.local.voicehall.api

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

class OpsRepository(
    private val dataSource: DataSource,
    private val deviceCrypto: DeviceCrypto,
) {
    fun bootstrap(
        organizationName: String,
        username: String,
        password: String,
        displayName: String,
    ) {
        dataSource.transaction { connection ->
            val exists = connection.prepareStatement("SELECT COUNT(*) FROM organizations").use { statement ->
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) > 0 }
            }
            if (exists) return@transaction
            val now = System.currentTimeMillis()
            val organizationId = id()
            val accountId = id()
            connection.prepareStatement(
                "INSERT INTO organizations(id,name,created_at) VALUES (?,?,?)",
            ).use {
                it.setString(1, organizationId)
                it.setString(2, organizationName)
                it.setLong(3, now)
                it.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO accounts(id,organization_id,username,display_name,password_hash,role,enabled,created_at)
                VALUES (?,?,?,?,?,?,TRUE,?)
                """.trimIndent(),
            ).use {
                it.setString(1, accountId)
                it.setString(2, organizationId)
                it.setString(3, username)
                it.setString(4, displayName)
                it.setString(5, Passwords.hash(password.toCharArray()))
                it.setString(6, Roles.OWNER)
                it.setLong(7, now)
                it.executeUpdate()
            }
            val roomId = id()
            connection.prepareStatement(
                "INSERT INTO rooms(id,organization_id,name,platform,enabled,created_at) VALUES (?,?,?,?,TRUE,?)",
            ).use {
                it.setString(1, roomId)
                it.setString(2, organizationId)
                it.setString(3, "默认语音厅")
                it.setString(4, "ingkee")
                it.setLong(5, now)
                it.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO settlement_rules(
                    id,organization_id,name,effective_from,platform_rate_bps,
                    organization_share_bps,member_commission_bps,active,created_at
                ) VALUES (?,?,?,?,?,?,?,TRUE,?)
                """.trimIndent(),
            ).use {
                it.setString(1, id())
                it.setString(2, organizationId)
                it.setString(3, "默认结算规则")
                it.setLong(4, 0)
                it.setInt(5, 5000)
                it.setInt(6, 10000)
                it.setInt(7, 3000)
                it.setLong(8, now)
                it.executeUpdate()
            }
            listOf(
                ValueLevelRuleInput("standard", "普通", 0, 0, 0),
                ValueLevelRuleInput("important", "重点", 100_000, 300_000, 10),
                ValueLevelRuleInput("vip", "VIP", 1_000_000, 3_000_000, 20),
            ).forEach { rule -> insertValueLevel(connection, organizationId, rule) }
        }
    }

    fun findAccount(username: String): AuthAccount? = dataSource.connection { connection ->
        connection.prepareStatement(
            """
            SELECT id,organization_id,username,display_name,role,password_hash,enabled,totp_secret,token_version
            FROM accounts WHERE username = ?
            """.trimIndent(),
        ).use {
            it.setString(1, username.trim())
            it.executeQuery().use { rows -> if (rows.next()) rows.authAccount() else null }
        }
    }

    fun isAccessTokenValid(
        accountId: String,
        organizationId: String,
        tokenVersion: Int,
    ): Boolean = dataSource.connection { connection ->
        connection.prepareStatement(
            """
            SELECT COUNT(*) FROM accounts
            WHERE id=? AND organization_id=? AND enabled=TRUE AND token_version=?
            """.trimIndent(),
        ).use {
            it.setString(1, accountId)
            it.setString(2, organizationId)
            it.setInt(3, tokenVersion)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) == 1 }
        }
    }

    fun isLoginAllowed(username: String): Boolean = dataSource.connection { connection ->
        connection.prepareStatement(
            "SELECT blocked_until FROM auth_login_attempts WHERE login_key=?",
        ).use {
            it.setString(1, loginKey(username))
            it.executeQuery().use { rows ->
                !rows.next() || rows.getNullableLong("blocked_until")?.let { blocked -> blocked <= now() } != false
            }
        }
    }

    fun recordLoginFailure(username: String) {
        dataSource.transaction { connection ->
            val key = loginKey(username)
            val current = connection.prepareStatement(
                """
                SELECT failure_count,window_started_at FROM auth_login_attempts
                WHERE login_key=? FOR UPDATE
                """.trimIndent(),
            ).use {
                it.setString(1, key)
                it.executeQuery().use { rows ->
                    if (rows.next()) rows.getInt(1) to rows.getLong(2) else null
                }
            }
            val currentTime = now()
            val activeWindow = current?.takeIf { currentTime - it.second < LOGIN_WINDOW_MS }
            val failures = activeWindow?.first?.plus(1) ?: 1
            val windowStartedAt = activeWindow?.second ?: currentTime
            val blockedUntil = if (failures >= LOGIN_MAX_FAILURES) currentTime + LOGIN_BLOCK_MS else null
            if (current == null) {
                connection.prepareStatement(
                    """
                    INSERT INTO auth_login_attempts(login_key,failure_count,window_started_at,blocked_until)
                    VALUES (?,?,?,?)
                    """.trimIndent(),
                ).use {
                    it.setString(1, key)
                    it.setInt(2, failures)
                    it.setLong(3, windowStartedAt)
                    it.setNullableLong(4, blockedUntil)
                    it.executeUpdate()
                }
            } else {
                connection.prepareStatement(
                    """
                    UPDATE auth_login_attempts
                    SET failure_count=?,window_started_at=?,blocked_until=?
                    WHERE login_key=?
                    """.trimIndent(),
                ).use {
                    it.setInt(1, failures)
                    it.setLong(2, windowStartedAt)
                    it.setNullableLong(3, blockedUntil)
                    it.setString(4, key)
                    it.executeUpdate()
                }
            }
        }
    }

    fun clearLoginFailures(username: String) {
        dataSource.connection { connection ->
            connection.prepareStatement("DELETE FROM auth_login_attempts WHERE login_key=?").use {
                it.setString(1, loginKey(username))
                it.executeUpdate()
            }
        }
    }

    fun issueRefreshToken(account: AuthAccount): RefreshGrant =
        dataSource.transaction { connection -> createRefreshGrant(connection, account) }

    fun rotateRefreshToken(refreshToken: String): RefreshGrant =
        dataSource.transaction { connection ->
            require(refreshToken.length in 32..256) { "Invalid refresh token" }
            val account = connection.prepareStatement(
                """
                SELECT a.id,a.organization_id,a.username,a.display_name,a.role,a.password_hash,
                       a.enabled,a.totp_secret,a.token_version,t.id token_id,t.expires_at,t.revoked_at
                FROM refresh_tokens t JOIN accounts a ON a.id=t.account_id
                WHERE t.token_hash=? FOR UPDATE
                """.trimIndent(),
            ).use {
                it.setString(1, sha256(refreshToken))
                it.executeQuery().use { rows ->
                    check(rows.next()) { "Refresh token is invalid" }
                    check(rows.getNullableLong("revoked_at") == null) { "Refresh token was revoked" }
                    check(rows.getLong("expires_at") > now()) { "Refresh token expired" }
                    check(rows.getBoolean("enabled")) { "Account is disabled" }
                    rows.authAccount() to rows.getString("token_id")
                }
            }
            connection.prepareStatement("UPDATE refresh_tokens SET revoked_at=? WHERE id=?").use {
                it.setLong(1, now())
                it.setString(2, account.second)
                it.executeUpdate()
            }
            createRefreshGrant(connection, account.first)
        }

    fun revokeRefreshToken(refreshToken: String) {
        if (refreshToken.isBlank()) return
        dataSource.connection { connection ->
            connection.prepareStatement(
                "UPDATE refresh_tokens SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL",
            ).use {
                it.setLong(1, now())
                it.setString(2, sha256(refreshToken))
                it.executeUpdate()
            }
        }
    }

    fun changePassword(
        identity: RequestIdentity,
        currentPassword: String,
        newPassword: String,
    ) {
        require(newPassword.length >= 10) { "New password must contain at least 10 characters" }
        require(newPassword != currentPassword) { "New password must be different" }
        dataSource.transaction { connection ->
            val passwordHash = connection.prepareStatement(
                """
                SELECT password_hash FROM accounts
                WHERE id=? AND organization_id=? AND enabled=TRUE FOR UPDATE
                """.trimIndent(),
            ).use {
                it.setString(1, identity.accountId)
                it.setString(2, identity.organizationId)
                it.executeQuery().use { rows ->
                    check(rows.next()) { "Account does not exist" }
                    rows.getString(1)
                }
            }
            check(Passwords.verify(passwordHash, currentPassword.toCharArray())) {
                "Current password is incorrect"
            }
            connection.prepareStatement(
                """
                UPDATE accounts
                SET password_hash=?,token_version=token_version+1
                WHERE id=? AND organization_id=?
                """.trimIndent(),
            ).use {
                it.setString(1, Passwords.hash(newPassword.toCharArray()))
                it.setString(2, identity.accountId)
                it.setString(3, identity.organizationId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "UPDATE refresh_tokens SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL",
            ).use {
                it.setLong(1, now())
                it.setString(2, identity.accountId)
                it.executeUpdate()
            }
            audit(connection, identity, "auth.password.change", "account", identity.accountId, "Password changed")
        }
    }

    fun getAccount(identity: RequestIdentity): AccountView = dataSource.connection { connection ->
        connection.prepareStatement(
            "SELECT id,organization_id,username,display_name,role FROM accounts WHERE id=? AND organization_id=?",
        ).use {
            it.setString(1, identity.accountId)
            it.setString(2, identity.organizationId)
            it.executeQuery().use { rows ->
                check(rows.next()) { "账号不存在" }
                rows.accountView()
            }
        }
    }

    fun listAccounts(identity: RequestIdentity): List<AccountView> = dataSource.connection { connection ->
        connection.prepareStatement(
            "SELECT id,organization_id,username,display_name,role FROM accounts WHERE organization_id=? ORDER BY created_at",
        ).use {
            it.setString(1, identity.organizationId)
            it.executeQuery().use { rows -> rows.map { accountView() } }
        }
    }

    fun createAccount(identity: RequestIdentity, input: AccountCreateInput): AccountView {
        require(input.username.matches(Regex("[A-Za-z0-9_.-]{3,40}"))) { "用户名格式无效" }
        require(input.displayName.trim().length in 1..80) { "显示名称无效" }
        require(input.password.length >= 10) { "密码至少 10 位" }
        require(input.role in setOf(Roles.ADMIN, Roles.SCHEDULER, Roles.FINANCE, Roles.MEMBER, Roles.AUDITOR)) {
            "角色无效"
        }
        val totpSecret = input.totpSecret
            ?.takeIf { it.isNotBlank() }
            ?.let(Totp::normalizeAndValidate)
        return dataSource.transaction { connection ->
            val account = AccountView(id(), identity.organizationId, input.username, input.displayName.trim(), input.role)
            connection.prepareStatement(
                """
                INSERT INTO accounts(
                    id,organization_id,username,display_name,password_hash,role,enabled,created_at,totp_secret
                ) VALUES (?,?,?,?,?,?,TRUE,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, account.id)
                it.setString(2, account.organizationId)
                it.setString(3, account.username)
                it.setString(4, account.displayName)
                it.setString(5, Passwords.hash(input.password.toCharArray()))
                it.setString(6, account.role)
                it.setLong(7, now())
                it.setString(8, totpSecret)
                it.executeUpdate()
            }
            input.roomIds.distinct().forEach { roomId ->
                requireRoom(connection, identity.organizationId, roomId)
                connection.prepareStatement(
                    "INSERT INTO account_room_scopes(account_id,room_id) VALUES (?,?)",
                ).use {
                    it.setString(1, account.id)
                    it.setString(2, roomId)
                    it.executeUpdate()
                }
            }
            audit(connection, identity, "account.create", "account", account.id, "创建账号 ${account.username}/${account.role}")
            account
        }
    }

    fun listRooms(identity: RequestIdentity): List<RoomView> = dataSource.connection { connection ->
        val sql = if (hasGlobalRoomAccess(identity.role)) {
            "SELECT id,name,platform,external_room_id,enabled FROM rooms WHERE organization_id=? ORDER BY created_at"
        } else {
            """
            SELECT r.id,r.name,r.platform,r.external_room_id,r.enabled
            FROM rooms r JOIN account_room_scopes s ON s.room_id=r.id
            WHERE r.organization_id=? AND s.account_id=? ORDER BY r.created_at
            """.trimIndent()
        }
        connection.prepareStatement(sql).use {
            it.setString(1, identity.organizationId)
            if (!hasGlobalRoomAccess(identity.role)) it.setString(2, identity.accountId)
            it.executeQuery().use { rows -> rows.map { roomView() } }
        }
    }

    fun createRoom(identity: RequestIdentity, input: RoomInput): RoomView {
        require(input.name.trim().length in 2..80)
        require(input.platform in setOf("ingkee", "wechat", "other"))
        return dataSource.transaction { connection ->
            val room = RoomView(id(), input.name.trim(), input.platform, input.externalRoomId?.trim())
            connection.prepareStatement(
                """
                INSERT INTO rooms(id,organization_id,name,platform,external_room_id,enabled,created_at)
                VALUES (?,?,?,?,?,TRUE,?)
                """.trimIndent(),
            ).use {
                it.setString(1, room.id)
                it.setString(2, identity.organizationId)
                it.setString(3, room.name)
                it.setString(4, room.platform)
                it.setNullableString(5, room.externalRoomId)
                it.setLong(6, now())
                it.executeUpdate()
            }
            audit(connection, identity, "room.create", "room", room.id, "创建厅房 ${room.name}")
            room
        }
    }

    fun listShifts(identity: RequestIdentity, roomId: String?): List<ShiftView> =
        dataSource.connection { connection ->
            roomId?.let { requireRoomAccess(connection, identity, it) }
            val sql = buildString {
                append("SELECT id,room_id,host_account_id,title,start_at,end_at,status,host_fixed_cents,host_hourly_cents,checked_in_at FROM shifts WHERE organization_id=?")
                if (roomId != null) append(" AND room_id=?")
                if (roomId == null && !hasGlobalRoomAccess(identity.role)) {
                    append(" AND room_id IN (SELECT room_id FROM account_room_scopes WHERE account_id=?)")
                }
                append(" ORDER BY start_at DESC")
            }
            connection.prepareStatement(sql).use {
                it.setString(1, identity.organizationId)
                if (roomId != null) it.setString(2, roomId)
                if (roomId == null && !hasGlobalRoomAccess(identity.role)) {
                    it.setString(2, identity.accountId)
                }
                it.executeQuery().use { rows -> rows.map { shiftView() } }
            }
        }

    fun createShift(identity: RequestIdentity, input: ShiftInput): ShiftView {
        require(input.endAtEpochMs > input.startAtEpochMs) { "结束时间必须晚于开始时间" }
        require(input.hostFixedCents >= 0 && input.hostHourlyCents >= 0) { "费用不能为负数" }
        return dataSource.transaction { connection ->
            requireRoomAccess(connection, identity, input.roomId)
            val overlap = connection.prepareStatement(
                """
                SELECT COUNT(*) FROM shifts
                WHERE organization_id=? AND room_id=? AND status NOT IN ('cancelled','completed')
                  AND start_at < ? AND end_at > ?
                """.trimIndent(),
            ).use {
                it.setString(1, identity.organizationId)
                it.setString(2, input.roomId)
                it.setLong(3, input.endAtEpochMs)
                it.setLong(4, input.startAtEpochMs)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) > 0 }
            }
            check(!overlap) { "该厅房时段与现有班次冲突" }
            val shift = ShiftView(
                id = id(),
                roomId = input.roomId,
                hostAccountId = input.hostAccountId,
                title = input.title.trim(),
                startAtEpochMs = input.startAtEpochMs,
                endAtEpochMs = input.endAtEpochMs,
                status = "scheduled",
                hostFixedCents = input.hostFixedCents,
                hostHourlyCents = input.hostHourlyCents,
            )
            connection.prepareStatement(
                """
                INSERT INTO shifts(
                    id,organization_id,room_id,host_account_id,title,start_at,end_at,status,
                    host_fixed_cents,host_hourly_cents,created_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, shift.id)
                it.setString(2, identity.organizationId)
                it.setString(3, shift.roomId)
                it.setNullableString(4, shift.hostAccountId)
                it.setString(5, shift.title)
                it.setLong(6, shift.startAtEpochMs)
                it.setLong(7, shift.endAtEpochMs)
                it.setString(8, shift.status)
                it.setLong(9, shift.hostFixedCents)
                it.setLong(10, shift.hostHourlyCents)
                it.setLong(11, now())
                it.executeUpdate()
            }
            audit(connection, identity, "shift.create", "shift", shift.id, "创建班次 ${shift.title}")
            shift
        }
    }

    fun listCustomers(identity: RequestIdentity): List<CustomerView> = dataSource.connection { connection ->
        val now = now()
        val roomScope = if (hasGlobalRoomAccess(identity.role)) "" else """
            AND (
                EXISTS (
                    SELECT 1 FROM interaction_events i
                    JOIN account_room_scopes s ON s.room_id=i.room_id
                    WHERE i.customer_id=c.id AND s.account_id=?
                )
                OR EXISTS (
                    SELECT 1 FROM revenue_lines scoped_revenue
                    JOIN account_room_scopes s ON s.room_id=scoped_revenue.room_id
                    WHERE scoped_revenue.customer_id=c.id AND s.account_id=?
                )
                OR EXISTS (
                    SELECT 1 FROM tasks scoped_task
                    JOIN account_room_scopes s ON s.room_id=scoped_task.room_id
                    WHERE scoped_task.customer_id=c.id AND s.account_id=?
                )
            )
        """.trimIndent()
        connection.prepareStatement(
            """
            SELECT c.id,c.display_name,c.relationship_stage,c.contact_eligibility,c.last_interaction_at,
                   p.platform,p.external_user_id,
                   COALESCE(SUM(CASE WHEN r.occurred_at>=? THEN r.gross_cents ELSE 0 END),0) revenue7,
                   COALESCE(SUM(CASE WHEN r.occurred_at>=? THEN r.gross_cents ELSE 0 END),0) revenue30,
                   COALESCE(SUM(CASE WHEN r.occurred_at>=? THEN r.gross_cents ELSE 0 END),0) revenue90,
                   COALESCE(SUM(r.gross_cents),0) lifetime
            FROM customers c
            JOIN customer_platform_accounts p ON p.customer_id=c.id
            LEFT JOIN revenue_lines r ON r.customer_id=c.id
            WHERE c.organization_id=?
            $roomScope
            GROUP BY c.id,c.display_name,c.relationship_stage,c.contact_eligibility,c.last_interaction_at,p.platform,p.external_user_id
            ORDER BY revenue30 DESC,c.updated_at DESC
            """.trimIndent(),
        ).use {
            it.setLong(1, now - 7L * DAY_MS)
            it.setLong(2, now - 30L * DAY_MS)
            it.setLong(3, now - 90L * DAY_MS)
            it.setString(4, identity.organizationId)
            if (!hasGlobalRoomAccess(identity.role)) {
                it.setString(5, identity.accountId)
                it.setString(6, identity.accountId)
                it.setString(7, identity.accountId)
            }
            it.executeQuery().use { rows ->
                rows.map {
                    val revenue30 = getLong("revenue30")
                    val lifetime = getLong("lifetime")
                    CustomerView(
                        id = getString("id"),
                        displayName = getString("display_name"),
                        platform = getString("platform"),
                        externalUserId = getString("external_user_id"),
                        relationshipStage = getString("relationship_stage"),
                        contactEligibility = getString("contact_eligibility"),
                        lastInteractionAtEpochMs = getNullableLong("last_interaction_at"),
                        revenue7dCents = getLong("revenue7"),
                        revenue30dCents = revenue30,
                        revenue90dCents = getLong("revenue90"),
                        lifetimeRevenueCents = lifetime,
                        valueLevel = valueLevel(connection, identity.organizationId, revenue30, lifetime),
                    )
                }
            }
        }
    }

    fun createCustomer(identity: RequestIdentity, input: CustomerInput): CustomerView =
        dataSource.transaction { connection ->
            require(input.displayName.trim().isNotEmpty())
            require(input.externalUserId.trim().isNotEmpty())
            val existing = findCustomerByPlatform(
                connection,
                identity.organizationId,
                input.platform,
                input.externalUserId,
            )
            check(existing == null) { "该平台用户已存在" }
            val customerId = id()
            val now = now()
            connection.prepareStatement(
                """
                INSERT INTO customers(
                    id,organization_id,display_name,relationship_stage,contact_eligibility,created_at,updated_at
                ) VALUES (?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, customerId)
                it.setString(2, identity.organizationId)
                it.setString(3, input.displayName.trim())
                it.setString(4, input.relationshipStage)
                it.setString(5, input.contactEligibility)
                it.setLong(6, now)
                it.setLong(7, now)
                it.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO customer_platform_accounts(
                    id,organization_id,customer_id,platform,external_user_id,display_name
                ) VALUES (?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, id())
                it.setString(2, identity.organizationId)
                it.setString(3, customerId)
                it.setString(4, input.platform)
                it.setString(5, input.externalUserId.trim())
                it.setString(6, input.platformDisplayName.trim())
                it.executeUpdate()
            }
            audit(connection, identity, "customer.create", "customer", customerId, "创建客户 ${input.displayName}")
            CustomerView(
                customerId,
                input.displayName.trim(),
                input.platform,
                input.externalUserId.trim(),
                input.relationshipStage,
                input.contactEligibility,
            )
        }

    fun listTasks(identity: RequestIdentity, state: String?): List<TaskView> =
        dataSource.connection { connection ->
            val conditions = mutableListOf("t.organization_id=?")
            if (state != null) conditions += "t.state=?"
            if (!hasGlobalRoomAccess(identity.role)) {
                conditions += "t.room_id IN (SELECT room_id FROM account_room_scopes WHERE account_id=?)"
            }
            if (identity.role == Roles.MEMBER) {
                conditions += "((t.state='published' AND t.assigned_account_id IS NULL) OR t.assigned_account_id=?)"
            }
            val sql =
                """
                SELECT t.id,t.room_id,t.customer_id,c.display_name,t.title,t.brief,t.state,t.priority,
                       t.assigned_account_id,t.result_channel,t.result_note,t.next_follow_up_at,t.version,
                       COALESCE(SUM(CASE WHEN r.occurred_at>=? THEN r.gross_cents ELSE 0 END),0) revenue30,
                       COALESCE(SUM(r.gross_cents),0) lifetime
                FROM tasks t JOIN customers c ON c.id=t.customer_id
                LEFT JOIN revenue_lines r ON r.customer_id=c.id
                WHERE ${conditions.joinToString(" AND ")}
                GROUP BY t.id,t.room_id,t.customer_id,c.display_name,t.title,t.brief,t.state,t.priority,
                         t.assigned_account_id,t.result_channel,t.result_note,t.next_follow_up_at,t.version
                ORDER BY t.priority DESC,t.created_at
                """.trimIndent()
            connection.prepareStatement(sql).use {
                var index = 1
                it.setLong(index++, now() - 30L * DAY_MS)
                it.setString(index++, identity.organizationId)
                if (state != null) it.setString(index++, state)
                if (!hasGlobalRoomAccess(identity.role)) it.setString(index++, identity.accountId)
                if (identity.role == Roles.MEMBER) it.setString(index, identity.accountId)
                it.executeQuery().use { rows ->
                    rows.map {
                        val revenue30 = getLong("revenue30")
                        val lifetime = getLong("lifetime")
                        val exact = Roles.allows(identity.role, "amounts.exact")
                        TaskView(
                            id = getString("id"),
                            roomId = getString("room_id"),
                            customerId = getString("customer_id"),
                            customerName = getString("display_name"),
                            title = getString("title"),
                            brief = getString("brief"),
                            state = getString("state"),
                            priority = getInt("priority"),
                            assignedAccountId = getString("assigned_account_id"),
                            resultChannel = getString("result_channel"),
                            resultNote = getString("result_note"),
                            nextFollowUpAtEpochMs = getNullableLong("next_follow_up_at"),
                            valueLevel = valueLevel(connection, identity.organizationId, revenue30, lifetime),
                            visibleRevenueCents = if (exact) lifetime else null,
                            version = getInt("version"),
                        )
                    }
                }
            }
        }

    fun createTask(identity: RequestIdentity, input: TaskInput): TaskView =
        dataSource.transaction { connection ->
            input.roomId?.let { requireRoomAccess(connection, identity, it) }
            if (!hasGlobalRoomAccess(identity.role)) {
                check(input.roomId != null) { "A room-scoped task must specify a room" }
            }
            val customer = connection.prepareStatement(
                """
                SELECT display_name,contact_eligibility FROM customers
                WHERE id=? AND organization_id=? FOR UPDATE
                """.trimIndent(),
            ).use {
                it.setString(1, input.customerId)
                it.setString(2, identity.organizationId)
                it.executeQuery().use { rows ->
                    check(rows.next()) { "客户不存在" }
                    rows.getString(1) to rows.getString(2)
                }
            }
            check(customer.second != "do_not_contact") { "该客户已禁止联系" }
            val recentContacts = connection.prepareStatement(
                """
                SELECT COUNT(*) FROM tasks
                WHERE organization_id=? AND customer_id=? AND state='approved' AND reviewed_at>=?
                """.trimIndent(),
            ).use {
                it.setString(1, identity.organizationId)
                it.setString(2, input.customerId)
                it.setLong(3, now() - CONTACT_WINDOW_MS)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            check(recentContacts < CUSTOMER_CONTACT_LIMIT) {
                "Customer contact limit reached for the last 7 days"
            }
            input.assignedAccountId?.let { accountId ->
                requireAssignableMember(connection, identity.organizationId, accountId, input.roomId)
                check(memberDailyContactCount(connection, identity.organizationId, accountId) < MEMBER_DAILY_LIMIT) {
                    "Member daily contact limit reached"
                }
            }
            val active = connection.prepareStatement(
                """
                SELECT COUNT(*) FROM tasks
                WHERE organization_id=? AND customer_id=? AND active_key=?
                """.trimIndent(),
            ).use {
                it.setString(1, identity.organizationId)
                it.setString(2, input.customerId)
                it.setString(3, ACTIVE_TASK_KEY)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            check(active == 0) { "该客户已有有效作业" }
            val task = TaskView(
                id = id(),
                roomId = input.roomId,
                customerId = input.customerId,
                customerName = customer.first,
                title = input.title.trim(),
                brief = input.brief.trim(),
                state = if (input.assignedAccountId != null) "assigned" else if (input.publish) "published" else "draft",
                priority = input.priority.coerceIn(0, 100),
                assignedAccountId = input.assignedAccountId,
                version = 0,
            )
            connection.prepareStatement(
                """
                INSERT INTO tasks(
                    id,organization_id,room_id,customer_id,title,brief,state,priority,
                    assigned_account_id,created_at,updated_at,version,active_key
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, task.id)
                it.setString(2, identity.organizationId)
                it.setNullableString(3, task.roomId)
                it.setString(4, task.customerId)
                it.setString(5, task.title)
                it.setString(6, task.brief)
                it.setString(7, task.state)
                it.setInt(8, task.priority)
                it.setNullableString(9, task.assignedAccountId)
                it.setLong(10, now())
                it.setLong(11, now())
                it.setInt(12, task.version)
                it.setNullableString(13, if (task.state == "draft") null else ACTIVE_TASK_KEY)
                it.executeUpdate()
            }
            audit(connection, identity, "task.create", "task", task.id, "创建作业 ${task.title}")
            task
        }

    fun transitionTask(
        identity: RequestIdentity,
        taskId: String,
        action: String,
        result: TaskResultInput? = null,
        expectedVersion: Int? = result?.expectedVersion,
        clientOperationId: String? = result?.clientOperationId,
    ): TaskView = dataSource.transaction { connection ->
        val current = taskForUpdate(connection, identity.organizationId, taskId)
        requireTaskRoomAccess(connection, identity, current.roomId)
        if (!clientOperationId.isNullOrBlank()) {
            require(clientOperationId.length <= 120) { "Client operation ID is too long" }
            val duplicate = connection.prepareStatement(
                """
                SELECT COUNT(*) FROM task_operations
                WHERE operation_id=? AND organization_id=? AND account_id=? AND task_id=? AND action=?
                """.trimIndent(),
            ).use {
                it.setString(1, clientOperationId)
                it.setString(2, identity.organizationId)
                it.setString(3, identity.accountId)
                it.setString(4, taskId)
                it.setString(5, action)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) == 1 }
            }
            if (duplicate) return@transaction current
        }
        expectedVersion?.let {
            check(it == current.version) { "Task version conflict; refresh and retry" }
        }
        if (identity.role == Roles.MEMBER && action in setOf("claim", "start")) {
            check(memberDailyContactCount(connection, identity.organizationId, identity.accountId) < MEMBER_DAILY_LIMIT) {
                "Member daily contact limit reached"
            }
        }
        val target = when (action) {
            "claim" -> {
                check(current.state == "published") { "只有任务池作业可以领取" }
                "claimed"
            }
            "start" -> {
                check(current.state in setOf("claimed", "assigned")) { "当前状态不能开始" }
                check(current.assignedAccountId == null || current.assignedAccountId == identity.accountId) { "作业已分配给其他成员" }
                "in_progress"
            }
            "submit" -> {
                check(current.assignedAccountId == identity.accountId) { "Task is assigned to another member" }
                check(current.state == "in_progress") { "当前状态不能提交" }
                requireNotNull(result) { "缺少完成结果" }
                "submitted"
            }
            "approve" -> {
                check(current.state == "submitted") { "只有已提交作业可以审核" }
                "approved"
            }
            "reject" -> {
                check(current.state == "submitted") { "只有已提交作业可以驳回" }
                "rejected"
            }
            "cancel" -> {
                check(current.state !in setOf("approved", "cancelled")) { "当前状态不能取消" }
                "cancelled"
            }
            else -> error("未知作业动作")
        }
        val assigned = when {
            action == "claim" -> identity.accountId
            action == "start" && current.assignedAccountId == null -> identity.accountId
            else -> current.assignedAccountId
        }
        connection.prepareStatement(
            """
            UPDATE tasks SET state=?,assigned_account_id=?,
                claimed_at=CASE WHEN ?='claim' THEN ? ELSE claimed_at END,
                started_at=CASE WHEN ?='start' THEN ? ELSE started_at END,
                submitted_at=CASE WHEN ?='submit' THEN ? ELSE submitted_at END,
                reviewed_at=CASE WHEN ? IN ('approve','reject') THEN ? ELSE reviewed_at END,
                result_channel=COALESCE(?,result_channel),result_note=COALESCE(?,result_note),
                next_follow_up_at=COALESCE(?,next_follow_up_at),updated_at=?,
                version=version+1,active_key=?
            WHERE id=? AND organization_id=? AND state=? AND version=?
            """.trimIndent(),
        ).use {
            val now = now()
            it.setString(1, target)
            it.setNullableString(2, assigned)
            it.setString(3, action); it.setLong(4, now)
            it.setString(5, action); it.setLong(6, now)
            it.setString(7, action); it.setLong(8, now)
            it.setString(9, action); it.setLong(10, now)
            it.setNullableString(11, result?.channel)
            it.setNullableString(12, result?.note)
            it.setNullableLong(13, result?.nextFollowUpAtEpochMs)
            it.setLong(14, now)
            it.setNullableString(15, if (target in TERMINAL_TASK_STATES) null else ACTIVE_TASK_KEY)
            it.setString(16, taskId)
            it.setString(17, identity.organizationId)
            it.setString(18, current.state)
            it.setInt(19, current.version)
            check(it.executeUpdate() == 1) { "作业已被其他人更新，请刷新" }
        }
        audit(connection, identity, "task.$action", "task", taskId, "作业状态 ${current.state} → $target")
        if (!clientOperationId.isNullOrBlank()) {
            connection.prepareStatement(
                """
                INSERT INTO task_operations(
                    operation_id,organization_id,account_id,task_id,action,resulting_version,created_at
                ) VALUES (?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, clientOperationId)
                it.setString(2, identity.organizationId)
                it.setString(3, identity.accountId)
                it.setString(4, taskId)
                it.setString(5, action)
                it.setInt(6, current.version + 1)
                it.setLong(7, now())
                it.executeUpdate()
            }
        }
        current.copy(
            state = target,
            assignedAccountId = assigned,
            resultChannel = result?.channel ?: current.resultChannel,
            resultNote = result?.note ?: current.resultNote,
            nextFollowUpAtEpochMs = result?.nextFollowUpAtEpochMs ?: current.nextFollowUpAtEpochMs,
            version = current.version + 1,
        )
    }

    fun previewRevenue(identity: RequestIdentity, request: RevenuePreviewRequest): RevenuePreviewResponse =
        dataSource.transaction { connection ->
            require(request.fileSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "文件 SHA-256 无效" }
            require(request.rows.isNotEmpty()) { "账单没有数据行" }
            require(request.rows.size <= 50_000) { "单次最多导入 50000 行" }
            val existing = connection.prepareStatement(
                "SELECT id FROM revenue_imports WHERE organization_id=? AND file_sha256=?",
            ).use {
                it.setString(1, identity.organizationId)
                it.setString(2, request.fileSha256.lowercase())
                it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            check(existing == null) { "该文件已经导入" }
            val errors = mutableListOf<String>()
            var duplicateCount = 0
            val calculated = request.rows.sumOf { row ->
                if (row.grossCents < 0) errors += "流水金额不能为负数"
                row.grossCents.coerceAtLeast(0)
            }
            request.rows.map(RevenueRowInput::roomId).distinct().forEach {
                runCatching { requireRoomAccess(connection, identity, it) }
                    .onFailure { errors += "厅房不存在或无权限：$it" }
            }
            val importId = id()
            connection.prepareStatement(
                """
                INSERT INTO revenue_imports(
                    id,organization_id,file_name,file_sha256,expected_total_cents,calculated_total_cents,
                    row_count,duplicate_count,state,created_by,created_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, importId)
                it.setString(2, identity.organizationId)
                it.setString(3, request.fileName)
                it.setString(4, request.fileSha256.lowercase())
                it.setLong(5, request.expectedTotalCents)
                it.setLong(6, calculated)
                it.setInt(7, request.rows.size)
                it.setInt(8, 0)
                it.setString(9, "processing")
                it.setString(10, identity.accountId)
                it.setLong(11, now())
                it.executeUpdate()
            }
            request.rows.forEachIndexed { index, row ->
                val fingerprint = revenueFingerprint(row)
                val duplicate = connection.prepareStatement(
                    "SELECT COUNT(*) FROM revenue_lines WHERE organization_id=? AND platform=? AND row_fingerprint=?",
                ).use {
                    it.setString(1, identity.organizationId)
                    it.setString(2, row.platform)
                    it.setString(3, fingerprint)
                    it.executeQuery().use { rows -> rows.next(); rows.getInt(1) > 0 }
                }
                if (duplicate) duplicateCount++
                connection.prepareStatement(
                    """
                    INSERT INTO revenue_import_rows(
                        id,import_id,organization_id,room_id,platform,transaction_id,row_fingerprint,
                        customer_external_id,customer_display_name,gift_category,gross_cents,occurred_at,duplicate
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent(),
                ).use {
                    it.setString(1, "$importId:$index")
                    it.setString(2, importId)
                    it.setString(3, identity.organizationId)
                    it.setString(4, row.roomId)
                    it.setString(5, row.platform)
                    it.setNullableString(6, row.transactionId)
                    it.setString(7, fingerprint)
                    it.setNullableString(8, row.customerExternalId)
                    it.setNullableString(9, row.customerDisplayName)
                    it.setNullableString(10, row.giftCategory)
                    it.setLong(11, row.grossCents)
                    it.setLong(12, row.occurredAtEpochMs)
                    it.setBoolean(13, duplicate)
                    it.executeUpdate()
                }
            }
            if (calculated != request.expectedTotalCents) {
                errors += "账单合计 ${calculated} 分与声明总额 ${request.expectedTotalCents} 分不一致"
            }
            connection.prepareStatement(
                """
                UPDATE revenue_imports
                SET duplicate_count=?, state=?
                WHERE id=? AND organization_id=?
                """.trimIndent(),
            ).use {
                it.setInt(1, duplicateCount)
                it.setString(2, if (errors.isEmpty()) "previewed" else "invalid")
                it.setString(3, importId)
                it.setString(4, identity.organizationId)
                it.executeUpdate()
            }
            audit(connection, identity, "revenue.preview", "revenue_import", importId, "预览账单 ${request.fileName}")
            RevenuePreviewResponse(
                importId,
                request.rows.size,
                request.rows.size - duplicateCount,
                duplicateCount,
                request.expectedTotalCents,
                calculated,
                errors.isEmpty(),
                errors.distinct(),
            )
        }

    fun commitRevenue(identity: RequestIdentity, importId: String): RevenuePreviewResponse =
        dataSource.transaction { connection ->
            val import = connection.prepareStatement(
                """
                SELECT row_count,duplicate_count,expected_total_cents,calculated_total_cents,state
                FROM revenue_imports WHERE id=? AND organization_id=?
                """.trimIndent(),
            ).use {
                it.setString(1, importId)
                it.setString(2, identity.organizationId)
                it.executeQuery().use { rows ->
                    check(rows.next()) { "导入任务不存在" }
                    ImportState(rows.getInt(1), rows.getInt(2), rows.getLong(3), rows.getLong(4), rows.getString(5))
                }
            }
            check(import.state == "previewed") { "只有校验通过的预览可以提交" }
            check(import.expected == import.calculated) { "账单总额不一致，禁止提交" }
            connection.prepareStatement(
                """
                SELECT id,room_id,platform,transaction_id,row_fingerprint,customer_external_id,
                       customer_display_name,gift_category,gross_cents,occurred_at
                FROM revenue_import_rows WHERE import_id=? AND organization_id=? AND duplicate=FALSE
                """.trimIndent(),
            ).use {
                it.setString(1, importId)
                it.setString(2, identity.organizationId)
                it.executeQuery().use { rows ->
                    while (rows.next()) {
                        val platform = rows.getString("platform")
                        val externalId = rows.getString("customer_external_id")
                        val customerId = if (!externalId.isNullOrBlank()) {
                            findCustomerByPlatform(connection, identity.organizationId, platform, externalId)
                                ?: createCustomerFromRevenue(
                                    connection,
                                    identity.organizationId,
                                    platform,
                                    externalId,
                                    rows.getString("customer_display_name") ?: externalId,
                                )
                        } else null
                        connection.prepareStatement(
                            """
                            INSERT INTO revenue_lines(
                                id,organization_id,import_id,room_id,customer_id,platform,transaction_id,
                                row_fingerprint,gift_category,gross_cents,occurred_at
                            ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                            """.trimIndent(),
                        ).use { insert ->
                            insert.setString(1, rows.getString("id"))
                            insert.setString(2, identity.organizationId)
                            insert.setString(3, importId)
                            insert.setString(4, rows.getString("room_id"))
                            insert.setNullableString(5, customerId)
                            insert.setString(6, platform)
                            insert.setNullableString(7, rows.getString("transaction_id"))
                            insert.setString(8, rows.getString("row_fingerprint"))
                            insert.setNullableString(9, rows.getString("gift_category"))
                            insert.setLong(10, rows.getLong("gross_cents"))
                            insert.setLong(11, rows.getLong("occurred_at"))
                            insert.executeUpdate()
                        }
                    }
                }
            }
            connection.prepareStatement(
                "UPDATE revenue_imports SET state='committed',committed_at=? WHERE id=?",
            ).use {
                it.setLong(1, now())
                it.setString(2, importId)
                it.executeUpdate()
            }
            audit(connection, identity, "revenue.commit", "revenue_import", importId, "提交官方账单")
            RevenuePreviewResponse(
                importId,
                import.rowCount,
                import.rowCount - import.duplicateCount,
                import.duplicateCount,
                import.expected,
                import.calculated,
                true,
                emptyList(),
            )
        }

    fun createSettlementRule(identity: RequestIdentity, input: SettlementRuleInput): String =
        dataSource.transaction { connection ->
            require(input.platformRateBps in 0..10_000)
            require(input.organizationShareBps in 0..10_000)
            require(input.memberCommissionBps in 0..10_000)
            val ruleId = id()
            connection.prepareStatement(
                """
                INSERT INTO settlement_rules(
                    id,organization_id,name,effective_from,platform_rate_bps,
                    organization_share_bps,member_commission_bps,active,created_at
                ) VALUES (?,?,?,?,?,?,?,TRUE,?)
                """.trimIndent(),
            ).use {
                it.setString(1, ruleId)
                it.setString(2, identity.organizationId)
                it.setString(3, input.name.trim())
                it.setLong(4, input.effectiveFromEpochMs)
                it.setInt(5, input.platformRateBps)
                it.setInt(6, input.organizationShareBps)
                it.setInt(7, input.memberCommissionBps)
                it.setLong(8, now())
                it.executeUpdate()
            }
            audit(connection, identity, "settlement_rule.create", "settlement_rule", ruleId, "创建结算规则 ${input.name}")
            ruleId
        }

    fun addExpense(identity: RequestIdentity, input: ExpenseInput): String =
        dataSource.transaction { connection ->
            require(input.amountCents >= 0)
            input.roomId?.let { requireRoomAccess(connection, identity, it) }
            val expenseId = id()
            connection.prepareStatement(
                """
                INSERT INTO expenses(id,organization_id,room_id,category,amount_cents,note,occurred_at,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, expenseId)
                it.setString(2, identity.organizationId)
                it.setNullableString(3, input.roomId)
                it.setString(4, input.category)
                it.setLong(5, input.amountCents)
                it.setString(6, input.note)
                it.setLong(7, input.occurredAtEpochMs)
                it.setLong(8, now())
                it.executeUpdate()
            }
            audit(connection, identity, "expense.create", "expense", expenseId, "登记支出 ${input.amountCents} 分")
            expenseId
        }

    fun previewSettlement(identity: RequestIdentity, input: SettlementPreviewRequest): SettlementView =
        dataSource.connection { connection ->
            require(input.periodEndEpochMs > input.periodStartEpochMs)
            input.roomId?.let { requireRoomAccess(connection, identity, it) }
            val rule = settlementRule(connection, identity.organizationId, input.ruleId, input.periodStartEpochMs)
            val gross = sumLong(
                connection,
                """
                SELECT COALESCE(SUM(gross_cents),0) FROM revenue_lines
                WHERE organization_id=? AND occurred_at>=? AND occurred_at<?
                ${if (input.roomId != null) "AND room_id=?" else ""}
                """.trimIndent(),
                identity.organizationId,
                input.periodStartEpochMs,
                input.periodEndEpochMs,
                input.roomId,
            )
            val expenses = sumLong(
                connection,
                """
                SELECT COALESCE(SUM(amount_cents),0) FROM expenses
                WHERE organization_id=? AND occurred_at>=? AND occurred_at<?
                ${if (input.roomId != null) "AND room_id=?" else ""}
                """.trimIndent(),
                identity.organizationId,
                input.periodStartEpochMs,
                input.periodEndEpochMs,
                input.roomId,
            )
            val hostCost = connection.prepareStatement(
                """
                SELECT host_fixed_cents,host_hourly_cents,start_at,end_at FROM shifts
                WHERE organization_id=? AND start_at<? AND end_at>?
                ${if (input.roomId != null) "AND room_id=?" else ""}
                AND status NOT IN ('cancelled')
                """.trimIndent(),
            ).use {
                it.setString(1, identity.organizationId)
                it.setLong(2, input.periodEndEpochMs)
                it.setLong(3, input.periodStartEpochMs)
                if (input.roomId != null) it.setString(4, input.roomId)
                it.executeQuery().use { rows ->
                    var total = 0L
                    while (rows.next()) {
                        val duration = (rows.getLong("end_at") - rows.getLong("start_at")).coerceAtLeast(0)
                        total += rows.getLong("host_fixed_cents")
                        total += FinanceCalculator.prorate(
                            rows.getLong("host_hourly_cents"),
                            duration,
                            HOUR_MS,
                        )
                    }
                    total
                }
            }
            FinanceCalculator.calculate(
                FinanceInputs(gross, rule.platformRate, rule.organizationShare, rule.memberCommission, hostCost, expenses),
                input.roomId,
                input.periodStartEpochMs,
                input.periodEndEpochMs,
                rule.id,
            )
        }

    fun closeSettlement(identity: RequestIdentity, input: SettlementPreviewRequest): SettlementView =
        dataSource.transaction { connection ->
            val preview = previewSettlement(identity, input)
            val overlap = connection.prepareStatement(
                """
                SELECT COUNT(*) FROM settlements WHERE organization_id=? AND state='closed'
                AND period_start < ? AND period_end > ?
                AND ((room_id IS NULL AND ? IS NULL) OR room_id=?)
                """.trimIndent(),
            ).use {
                it.setString(1, identity.organizationId)
                it.setLong(2, input.periodEndEpochMs)
                it.setLong(3, input.periodStartEpochMs)
                it.setNullableString(4, input.roomId)
                it.setNullableString(5, input.roomId)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) > 0 }
            }
            check(!overlap) { "该范围已存在关闭的结算期，只能创建调整单" }
            val settlementId = id()
            connection.prepareStatement(
                """
                INSERT INTO settlements(
                    id,organization_id,room_id,period_start,period_end,rule_id,gross_cents,
                    platform_deduction_cents,organization_share_cents,member_commission_cents,
                    host_cost_cents,expense_cents,accounts_receivable_cents,accounts_payable_cents,
                    net_profit_cents,state,closed_by,closed_at,created_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                var i = 1
                it.setString(i++, settlementId)
                it.setString(i++, identity.organizationId)
                it.setNullableString(i++, preview.roomId)
                it.setLong(i++, preview.periodStartEpochMs)
                it.setLong(i++, preview.periodEndEpochMs)
                it.setString(i++, preview.ruleId)
                it.setLong(i++, preview.grossCents)
                it.setLong(i++, preview.platformDeductionCents)
                it.setLong(i++, preview.organizationShareCents)
                it.setLong(i++, preview.memberCommissionCents)
                it.setLong(i++, preview.hostCostCents)
                it.setLong(i++, preview.expenseCents)
                it.setLong(i++, preview.accountsReceivableCents)
                it.setLong(i++, preview.accountsPayableCents)
                it.setLong(i++, preview.netProfitCents)
                it.setString(i++, "closed")
                it.setString(i++, identity.accountId)
                it.setLong(i++, now())
                it.setLong(i, now())
                it.executeUpdate()
            }
            audit(connection, identity, "settlement.close", "settlement", settlementId, "关闭结算期")
            preview.copy(id = settlementId, state = "closed")
        }

    fun addAdjustment(identity: RequestIdentity, settlementId: String, input: AdjustmentInput): String =
        dataSource.transaction { connection ->
            require(input.reason.trim().length in 2..500)
            val exists = connection.prepareStatement(
                "SELECT COUNT(*) FROM settlements WHERE id=? AND organization_id=? AND state='closed'",
            ).use {
                it.setString(1, settlementId)
                it.setString(2, identity.organizationId)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) == 1 }
            }
            check(exists) { "已关闭结算单不存在" }
            val adjustmentId = id()
            connection.prepareStatement(
                """
                INSERT INTO settlement_adjustments(
                    id,settlement_id,organization_id,amount_cents,reason,created_by,created_at
                ) VALUES (?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, adjustmentId)
                it.setString(2, settlementId)
                it.setString(3, identity.organizationId)
                it.setLong(4, input.amountCents)
                it.setString(5, input.reason.trim())
                it.setString(6, identity.accountId)
                it.setLong(7, now())
                it.executeUpdate()
            }
            audit(connection, identity, "settlement.adjust", "settlement", settlementId, "结算调整 ${input.amountCents} 分")
            adjustmentId
        }

    fun reportSummary(identity: RequestIdentity, start: Long, end: Long): ReportSummary =
        dataSource.connection { connection ->
            require(end > start)
            val scoped = !hasGlobalRoomAccess(identity.role)
            val roomScope = if (scoped) {
                "AND room_id IN (SELECT room_id FROM account_room_scopes WHERE account_id=?)"
            } else {
                ""
            }
            val gross = connection.prepareStatement(
                """
                SELECT COALESCE(SUM(gross_cents),0) FROM revenue_lines
                WHERE organization_id=? AND occurred_at>=? AND occurred_at<? $roomScope
                """.trimIndent(),
            ).use {
                it.setString(1, identity.organizationId)
                it.setLong(2, start)
                it.setLong(3, end)
                if (scoped) it.setString(4, identity.accountId)
                it.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
            val taskCounts = connection.prepareStatement(
                """
                SELECT COUNT(*) total,COALESCE(SUM(CASE WHEN state='approved' THEN 1 ELSE 0 END),0) approved
                FROM tasks WHERE organization_id=? AND created_at>=? AND created_at<? $roomScope
                """.trimIndent(),
            ).use {
                it.setString(1, identity.organizationId)
                it.setLong(2, start)
                it.setLong(3, end)
                if (scoped) it.setString(4, identity.accountId)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) to rows.getInt(2) }
            }
            val customerScope = if (scoped) {
                """
                AND (
                    EXISTS (
                        SELECT 1 FROM interaction_events i
                        JOIN account_room_scopes s ON s.room_id=i.room_id
                        WHERE i.customer_id=customers.id AND s.account_id=?
                    )
                    OR EXISTS (
                        SELECT 1 FROM revenue_lines r
                        JOIN account_room_scopes s ON s.room_id=r.room_id
                        WHERE r.customer_id=customers.id AND s.account_id=?
                    )
                    OR EXISTS (
                        SELECT 1 FROM tasks t
                        JOIN account_room_scopes s ON s.room_id=t.room_id
                        WHERE t.customer_id=customers.id AND s.account_id=?
                    )
                )
                """.trimIndent()
            } else {
                ""
            }
            val customerCount = connection.prepareStatement(
                "SELECT COUNT(*) FROM customers WHERE organization_id=? $customerScope",
            ).use {
                it.setString(1, identity.organizationId)
                if (scoped) {
                    it.setString(2, identity.accountId)
                    it.setString(3, identity.accountId)
                    it.setString(4, identity.accountId)
                }
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            val roomTotals = connection.prepareStatement(
                """
                SELECT r.id,r.name,COALESCE(SUM(l.gross_cents),0) gross
                FROM rooms r LEFT JOIN revenue_lines l ON l.room_id=r.id AND l.occurred_at>=? AND l.occurred_at<?
                WHERE r.organization_id=?
                ${if (scoped) "AND r.id IN (SELECT room_id FROM account_room_scopes WHERE account_id=?)" else ""}
                GROUP BY r.id,r.name ORDER BY gross DESC
                """.trimIndent(),
            ).use {
                it.setLong(1, start); it.setLong(2, end); it.setString(3, identity.organizationId)
                if (scoped) it.setString(4, identity.accountId)
                it.executeQuery().use { rows -> rows.map { RoomRevenue(getString(1), getString(2), getLong(3)) } }
            }
            val daily = connection.prepareStatement(
                """
                SELECT occurred_at,gross_cents FROM revenue_lines
                WHERE organization_id=? AND occurred_at>=? AND occurred_at<? $roomScope
                """.trimIndent(),
            ).use {
                it.setString(1, identity.organizationId); it.setLong(2, start); it.setLong(3, end)
                if (scoped) it.setString(4, identity.accountId)
                it.executeQuery().use { rows ->
                    val zone = BUSINESS_ZONE
                    rows.map { Instant.ofEpochMilli(getLong(1)).atZone(zone).toLocalDate().toString() to getLong(2) }
                        .groupBy({ it.first }, { it.second })
                        .map { DailyRevenue(it.key, it.value.sum()) }
                        .sortedBy(DailyRevenue::date)
                }
            }
            ReportSummary(start, end, gross, taskCounts.first, taskCounts.second, customerCount, roomTotals, daily)
        }

    fun registerDevice(identity: RequestIdentity, input: DeviceRegistrationInput): DeviceRegistrationResponse =
        dataSource.transaction { connection ->
            requireRoomAccess(connection, identity, input.roomId)
            val secret = deviceCrypto.newSecret()
            val deviceId = id()
            connection.prepareStatement(
                """
                INSERT INTO devices(id,organization_id,room_id,name,secret_ciphertext,enabled,created_at)
                VALUES (?,?,?,?,?,TRUE,?)
                """.trimIndent(),
            ).use {
                it.setString(1, deviceId)
                it.setString(2, identity.organizationId)
                it.setString(3, input.roomId)
                it.setString(4, input.name.trim())
                it.setString(5, deviceCrypto.encrypt(secret))
                it.setLong(6, now())
                it.executeUpdate()
            }
            audit(connection, identity, "device.register", "device", deviceId, "注册厅控设备 ${input.name}")
            DeviceRegistrationResponse(deviceId, secret)
        }

    fun listDevices(identity: RequestIdentity): List<DeviceView> =
        dataSource.connection { connection ->
            val roomScope = if (hasGlobalRoomAccess(identity.role)) {
                ""
            } else {
                "AND room_id IN (SELECT room_id FROM account_room_scopes WHERE account_id=?)"
            }
            connection.prepareStatement(
                """
                SELECT id,room_id,name,enabled,last_seen_at
                FROM devices
                WHERE organization_id=? $roomScope
                ORDER BY created_at DESC
                """.trimIndent(),
            ).use {
                it.setString(1, identity.organizationId)
                if (!hasGlobalRoomAccess(identity.role)) it.setString(2, identity.accountId)
                it.executeQuery().use { rows ->
                    rows.map {
                        DeviceView(
                            id = getString("id"),
                            roomId = getString("room_id"),
                            name = getString("name"),
                            enabled = getBoolean("enabled"),
                            lastSeenAtEpochMs = getNullableLong("last_seen_at"),
                        )
                    }
                }
            }
        }

    fun disableDevice(identity: RequestIdentity, deviceId: String): DeviceView =
        dataSource.transaction { connection ->
            val device = connection.prepareStatement(
                """
                SELECT id,room_id,name,enabled,last_seen_at
                FROM devices
                WHERE id=? AND organization_id=? FOR UPDATE
                """.trimIndent(),
            ).use {
                it.setString(1, deviceId)
                it.setString(2, identity.organizationId)
                it.executeQuery().use { rows ->
                    check(rows.next()) { "Device does not exist" }
                    DeviceView(
                        id = rows.getString("id"),
                        roomId = rows.getString("room_id"),
                        name = rows.getString("name"),
                        enabled = rows.getBoolean("enabled"),
                        lastSeenAtEpochMs = rows.getNullableLong("last_seen_at"),
                    )
                }
            }
            requireRoomAccess(connection, identity, device.roomId)
            connection.prepareStatement(
                "UPDATE devices SET enabled=FALSE WHERE id=? AND organization_id=?",
            ).use {
                it.setString(1, deviceId)
                it.setString(2, identity.organizationId)
                it.executeUpdate()
            }
            audit(connection, identity, "device.disable", "device", deviceId, "Disabled device ${device.name}")
            device.copy(enabled = false)
        }

    fun verifyAndStoreDeviceEvent(
        deviceId: String,
        timestamp: String,
        signature: String,
        rawBody: String,
        event: DeviceEventInput,
    ): Boolean = dataSource.transaction { connection ->
        val timestampMs = timestamp.toLongOrNull() ?: error("设备时间戳无效")
        check(kotlin.math.abs(now() - timestampMs) <= 5 * 60_000L) { "设备请求已过期" }
        val device = connection.prepareStatement(
            "SELECT organization_id,room_id,secret_ciphertext,enabled FROM devices WHERE id=?",
        ).use {
            it.setString(1, deviceId)
            it.executeQuery().use { rows ->
                check(rows.next() && rows.getBoolean("enabled")) { "设备不存在或已禁用" }
                DeviceRow(rows.getString(1), rows.getString(2), rows.getString(3))
            }
        }
        check(event.roomId == device.roomId) { "设备无权写入其他厅房" }
        val secret = deviceCrypto.decrypt(device.encryptedSecret)
        check(deviceCrypto.verify(secret, timestamp, rawBody, signature)) { "设备签名无效" }
        val inserted = connection.prepareStatement(
            """
            INSERT INTO device_events(
                event_id,organization_id,room_id,device_id,event_type,payload,occurred_at,received_at
            ) VALUES (?,?,?,?,?,?,?,?)
            """.trimIndent(),
        ).use {
            it.setString(1, event.eventId)
            it.setString(2, device.organizationId)
            it.setString(3, device.roomId)
            it.setString(4, deviceId)
            it.setString(5, event.type)
            it.setString(6, event.payload)
            it.setLong(7, event.occurredAtEpochMs)
            it.setLong(8, now())
            runCatching { it.executeUpdate() }.getOrElse { 0 }
        }
        connection.prepareStatement("UPDATE devices SET last_seen_at=? WHERE id=?").use {
            it.setLong(1, now()); it.setString(2, deviceId); it.executeUpdate()
        }
        inserted == 1
    }

    fun listAudit(identity: RequestIdentity, limit: Int): List<AuditView> =
        dataSource.connection { connection ->
            connection.prepareStatement(
                """
                SELECT id,account_id,action,resource_type,resource_id,summary,created_at
                FROM audit_logs WHERE organization_id=? ORDER BY created_at DESC LIMIT ?
                """.trimIndent(),
            ).use {
                it.setString(1, identity.organizationId)
                it.setInt(2, limit.coerceIn(1, 500))
                it.executeQuery().use { rows ->
                    rows.map {
                        AuditView(
                            getString("id"),
                            getString("account_id"),
                            getString("action"),
                            getString("resource_type"),
                            getString("resource_id"),
                            getString("summary"),
                            getLong("created_at"),
                        )
                    }
                }
            }
        }

    fun saveLegacyMigration(identity: RequestIdentity, input: LegacyMigrationInput): String =
        dataSource.transaction { connection ->
            require(input.source in setOf("member-android-1.2.0", "control-android-0.1.0"))
            require(input.recordCount >= 0)
            val batchId = id()
            connection.prepareStatement(
                """
                INSERT INTO legacy_import_batches(
                    id,organization_id,source,source_version,record_count,payload_json,state,created_by,created_at
                ) VALUES (?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use {
                it.setString(1, batchId)
                it.setString(2, identity.organizationId)
                it.setString(3, input.source)
                it.setString(4, input.sourceVersion)
                it.setInt(5, input.recordCount)
                it.setString(6, input.payloadJson)
                it.setString(7, "uploaded")
                it.setString(8, identity.accountId)
                it.setLong(9, now())
                it.executeUpdate()
            }
            audit(connection, identity, "migration.upload", "legacy_import", batchId, "上传旧数据 ${input.source}")
            batchId
        }

    fun replaceValueLevels(identity: RequestIdentity, rules: List<ValueLevelRuleInput>) =
        dataSource.transaction { connection ->
            require(rules.isNotEmpty())
            connection.prepareStatement("DELETE FROM value_level_rules WHERE organization_id=?").use {
                it.setString(1, identity.organizationId)
                it.executeUpdate()
            }
            rules.sortedBy(ValueLevelRuleInput::sortOrder).forEach {
                require(it.minimum30dCents >= 0 && it.minimumLifetimeCents >= 0)
                insertValueLevel(connection, identity.organizationId, it)
            }
            audit(connection, identity, "value_levels.replace", "settings", null, "更新流水等级规则")
        }

    private fun createRefreshGrant(connection: Connection, account: AuthAccount): RefreshGrant {
        val refreshToken = SecureTokens.create()
        val expiresAt = now() + REFRESH_TTL_MS
        connection.prepareStatement(
            """
            INSERT INTO refresh_tokens(
                id,organization_id,account_id,token_hash,expires_at,created_at
            ) VALUES (?,?,?,?,?,?)
            """.trimIndent(),
        ).use {
            it.setString(1, id())
            it.setString(2, account.organizationId)
            it.setString(3, account.id)
            it.setString(4, sha256(refreshToken))
            it.setLong(5, expiresAt)
            it.setLong(6, now())
            it.executeUpdate()
        }
        return RefreshGrant(account, refreshToken, REFRESH_TTL_MS / 1_000L)
    }

    private fun requireAssignableMember(
        connection: Connection,
        organizationId: String,
        accountId: String,
        roomId: String?,
    ) {
        val accountRole = connection.prepareStatement(
            """
            SELECT role FROM accounts
            WHERE id=? AND organization_id=? AND enabled=TRUE
            """.trimIndent(),
        ).use {
            it.setString(1, accountId)
            it.setString(2, organizationId)
            it.executeQuery().use { rows ->
                check(rows.next()) { "Assigned account does not exist" }
                rows.getString(1)
            }
        }
        check(accountRole == Roles.MEMBER) { "Tasks can only be assigned to members" }
        if (roomId != null) {
            val hasScope = connection.prepareStatement(
                "SELECT COUNT(*) FROM account_room_scopes WHERE account_id=? AND room_id=?",
            ).use {
                it.setString(1, accountId)
                it.setString(2, roomId)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) == 1 }
            }
            check(hasScope) { "Assigned member does not have access to this room" }
        }
    }

    private fun memberDailyContactCount(
        connection: Connection,
        organizationId: String,
        accountId: String,
        currentTime: Long = now(),
    ): Int {
        val startOfDay = Instant.ofEpochMilli(currentTime)
            .atZone(BUSINESS_ZONE)
            .toLocalDate()
            .atStartOfDay(BUSINESS_ZONE)
            .toInstant()
            .toEpochMilli()
        return connection.prepareStatement(
            """
            SELECT COUNT(*) FROM tasks
            WHERE organization_id=? AND assigned_account_id=?
              AND state IN ('submitted','approved')
              AND submitted_at>=?
            """.trimIndent(),
        ).use {
            it.setString(1, organizationId)
            it.setString(2, accountId)
            it.setLong(3, startOfDay)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun requireTaskRoomAccess(
        connection: Connection,
        identity: RequestIdentity,
        roomId: String?,
    ) {
        if (roomId != null) {
            requireRoomAccess(connection, identity, roomId)
        } else {
            check(hasGlobalRoomAccess(identity.role)) { "Room-scoped accounts cannot access global tasks" }
        }
    }

    private fun taskForUpdate(connection: Connection, orgId: String, taskId: String): TaskView =
        connection.prepareStatement(
            """
            SELECT t.id,t.room_id,t.customer_id,c.display_name,t.title,t.brief,t.state,t.priority,
                   t.assigned_account_id,t.result_channel,t.result_note,t.next_follow_up_at,t.version
            FROM tasks t JOIN customers c ON c.id=t.customer_id
            WHERE t.id=? AND t.organization_id=? FOR UPDATE
            """.trimIndent(),
        ).use {
            it.setString(1, taskId); it.setString(2, orgId)
            it.executeQuery().use { rows ->
                check(rows.next()) { "作业不存在" }
                TaskView(
                    rows.getString("id"), rows.getString("room_id"), rows.getString("customer_id"),
                    rows.getString("display_name"), rows.getString("title"), rows.getString("brief"),
                    rows.getString("state"), rows.getInt("priority"), rows.getString("assigned_account_id"),
                    rows.getString("result_channel"), rows.getString("result_note"),
                    rows.getNullableLong("next_follow_up_at"),
                    version = rows.getInt("version"),
                )
            }
        }

    private fun settlementRule(connection: Connection, orgId: String, requested: String?, periodStart: Long): RuleRow {
        val sql = if (requested != null) {
            "SELECT id,platform_rate_bps,organization_share_bps,member_commission_bps FROM settlement_rules WHERE id=? AND organization_id=?"
        } else {
            """
            SELECT id,platform_rate_bps,organization_share_bps,member_commission_bps
            FROM settlement_rules WHERE organization_id=? AND active=TRUE AND effective_from<=?
            ORDER BY effective_from DESC LIMIT 1
            """.trimIndent()
        }
        return connection.prepareStatement(sql).use {
            if (requested != null) {
                it.setString(1, requested); it.setString(2, orgId)
            } else {
                it.setString(1, orgId); it.setLong(2, periodStart)
            }
            it.executeQuery().use { rows ->
                check(rows.next()) { "没有适用的结算规则" }
                RuleRow(rows.getString(1), rows.getInt(2), rows.getInt(3), rows.getInt(4))
            }
        }
    }

    private fun valueLevel(connection: Connection, orgId: String, revenue30: Long, lifetime: Long): String =
        connection.prepareStatement(
            """
            SELECT code FROM value_level_rules
            WHERE organization_id=? AND minimum_30d_cents<=? AND minimum_lifetime_cents<=?
            ORDER BY sort_order DESC LIMIT 1
            """.trimIndent(),
        ).use {
            it.setString(1, orgId); it.setLong(2, revenue30); it.setLong(3, lifetime)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else "standard" }
        }

    private fun insertValueLevel(connection: Connection, orgId: String, rule: ValueLevelRuleInput) {
        connection.prepareStatement(
            """
            INSERT INTO value_level_rules(
                id,organization_id,code,label,minimum_30d_cents,minimum_lifetime_cents,sort_order
            ) VALUES (?,?,?,?,?,?,?)
            """.trimIndent(),
        ).use {
            it.setString(1, id()); it.setString(2, orgId); it.setString(3, rule.code)
            it.setString(4, rule.label); it.setLong(5, rule.minimum30dCents)
            it.setLong(6, rule.minimumLifetimeCents); it.setInt(7, rule.sortOrder)
            it.executeUpdate()
        }
    }

    private fun requireRoomAccess(connection: Connection, identity: RequestIdentity, roomId: String) {
        requireRoom(connection, identity.organizationId, roomId)
        if (hasGlobalRoomAccess(identity.role)) return
        val allowed = connection.prepareStatement(
            "SELECT COUNT(*) FROM account_room_scopes WHERE account_id=? AND room_id=?",
        ).use {
            it.setString(1, identity.accountId); it.setString(2, roomId)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) > 0 }
        }
        check(allowed) { "无权访问该厅房" }
    }

    private fun requireRoom(connection: Connection, orgId: String, roomId: String) {
        val exists = connection.prepareStatement(
            "SELECT COUNT(*) FROM rooms WHERE id=? AND organization_id=? AND enabled=TRUE",
        ).use {
            it.setString(1, roomId); it.setString(2, orgId)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) == 1 }
        }
        check(exists) { "厅房不存在或已禁用" }
    }

    private fun findCustomerByPlatform(
        connection: Connection,
        orgId: String,
        platform: String,
        externalId: String,
    ): String? = connection.prepareStatement(
        """
        SELECT customer_id FROM customer_platform_accounts
        WHERE organization_id=? AND platform=? AND external_user_id=?
        """.trimIndent(),
    ).use {
        it.setString(1, orgId); it.setString(2, platform); it.setString(3, externalId)
        it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

    private fun createCustomerFromRevenue(
        connection: Connection,
        orgId: String,
        platform: String,
        externalId: String,
        displayName: String,
    ): String {
        val customerId = id()
        val now = now()
        connection.prepareStatement(
            """
            INSERT INTO customers(
                id,organization_id,display_name,relationship_stage,contact_eligibility,created_at,updated_at
            ) VALUES (?,?,?,?,?,?,?)
            """.trimIndent(),
        ).use {
            it.setString(1, customerId); it.setString(2, orgId); it.setString(3, displayName)
            it.setString(4, "gifted"); it.setString(5, "interaction"); it.setLong(6, now); it.setLong(7, now)
            it.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO customer_platform_accounts(
                id,organization_id,customer_id,platform,external_user_id,display_name
            ) VALUES (?,?,?,?,?,?)
            """.trimIndent(),
        ).use {
            it.setString(1, id()); it.setString(2, orgId); it.setString(3, customerId)
            it.setString(4, platform); it.setString(5, externalId); it.setString(6, displayName)
            it.executeUpdate()
        }
        return customerId
    }

    private fun revenueFingerprint(row: RevenueRowInput): String = sha256(
        listOf(
            row.platform,
            row.transactionId.orEmpty(),
            row.roomId,
            row.customerExternalId.orEmpty(),
            row.giftCategory.orEmpty(),
            row.grossCents.toString(),
            row.occurredAtEpochMs.toString(),
        ).joinToString("|"),
    )

    private fun audit(
        connection: Connection,
        identity: RequestIdentity,
        action: String,
        resourceType: String,
        resourceId: String?,
        summary: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO audit_logs(
                id,organization_id,account_id,action,resource_type,resource_id,summary,created_at
            ) VALUES (?,?,?,?,?,?,?,?)
            """.trimIndent(),
        ).use {
            it.setString(1, id()); it.setString(2, identity.organizationId)
            it.setString(3, identity.accountId); it.setString(4, action)
            it.setString(5, resourceType); it.setNullableString(6, resourceId)
            it.setString(7, summary); it.setLong(8, now()); it.executeUpdate()
        }
    }

    private fun sumLong(
        connection: Connection,
        sql: String,
        orgId: String,
        start: Long,
        end: Long,
        roomId: String?,
    ): Long = connection.prepareStatement(sql).use {
        it.setString(1, orgId); it.setLong(2, start); it.setLong(3, end)
        if (roomId != null) it.setString(4, roomId)
        it.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val HOUR_MS = 3_600_000L
        private const val REFRESH_TTL_MS = 30L * DAY_MS
        private const val LOGIN_WINDOW_MS = 15L * 60_000L
        private const val LOGIN_BLOCK_MS = 15L * 60_000L
        private const val LOGIN_MAX_FAILURES = 5
        private const val CONTACT_WINDOW_MS = 7L * DAY_MS
        private const val CUSTOMER_CONTACT_LIMIT = 2
        private const val MEMBER_DAILY_LIMIT = 20
        private const val ACTIVE_TASK_KEY = "active"
        private val TERMINAL_TASK_STATES = setOf("approved", "rejected", "cancelled")
        private val BUSINESS_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        private fun id(): String = UUID.randomUUID().toString()
        private fun now(): Long = System.currentTimeMillis()
        private fun loginKey(username: String): String = username.trim().lowercase().take(160)
        private fun hasGlobalRoomAccess(role: String) =
            role in setOf(Roles.OWNER, Roles.ADMIN, Roles.FINANCE, Roles.AUDITOR)
    }
}

private data class ImportState(
    val rowCount: Int,
    val duplicateCount: Int,
    val expected: Long,
    val calculated: Long,
    val state: String,
)

private data class RuleRow(
    val id: String,
    val platformRate: Int,
    val organizationShare: Int,
    val memberCommission: Int,
)

private data class DeviceRow(
    val organizationId: String,
    val roomId: String,
    val encryptedSecret: String,
)

private fun ResultSet.authAccount() = AuthAccount(
    getString("id"),
    getString("organization_id"),
    getString("username"),
    getString("display_name"),
    getString("role"),
    getString("password_hash"),
    getBoolean("enabled"),
    getString("totp_secret"),
    getInt("token_version"),
)

private fun ResultSet.accountView() = AccountView(
    getString("id"),
    getString("organization_id"),
    getString("username"),
    getString("display_name"),
    getString("role"),
)

private fun ResultSet.roomView() = RoomView(
    getString("id"),
    getString("name"),
    getString("platform"),
    getString("external_room_id"),
    getBoolean("enabled"),
)

private fun ResultSet.shiftView() = ShiftView(
    getString("id"),
    getString("room_id"),
    getString("host_account_id"),
    getString("title"),
    getLong("start_at"),
    getLong("end_at"),
    getString("status"),
    getLong("host_fixed_cents"),
    getLong("host_hourly_cents"),
    getNullableLong("checked_in_at"),
)

private inline fun <T> ResultSet.map(block: ResultSet.() -> T): List<T> {
    val result = mutableListOf<T>()
    while (next()) result += block()
    return result
}

private fun ResultSet.getNullableLong(column: String): Long? =
    getLong(column).let { if (wasNull()) null else it }

private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) setNull(index, java.sql.Types.VARCHAR) else setString(index, value)
}

private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setNull(index, java.sql.Types.BIGINT) else setLong(index, value)
}
