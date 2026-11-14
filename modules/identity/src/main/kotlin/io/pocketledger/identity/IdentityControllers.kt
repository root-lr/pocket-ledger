package io.pocketledger.identity

import io.pocketledger.platform.web.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val auth: AuthService, private val tokens: TokenService) {
    data class RegisterRequest(
        @field:Email @field:NotBlank val email: String,
        @field:Size(min = 10, max = 200) val password: String,
        val referenceCurrency: String? = null,
        val timezone: String? = null,
    )

    data class LoginRequest(
        @field:NotBlank val email: String,
        @field:NotBlank val password: String,
        val deviceId: UUID? = null,
        val deviceName: String? = null,
        val platform: String? = null,
    )

    data class RefreshRequest(@field:NotBlank val refreshToken: String)

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @Valid @RequestBody request: RegisterRequest,
    ): Map<String, UUID> =
        mapOf("userId" to auth.register(RegisterCommand(request.email, request.password, request.referenceCurrency, request.timezone)))

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
    ): TokenPair = auth.login(LoginCommand(request.email, request.password, request.deviceId, request.deviceName, request.platform))

    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
    ): TokenPair = tokens.rotate(request.refreshToken)

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(
        @Valid @RequestBody request: RefreshRequest,
    ) = tokens.revokeByToken(request.refreshToken)

    @GetMapping("/verify-email")
    fun verifyEmail(
        @RequestParam token: String,
    ): Map<String, Boolean> {
        auth.verifyEmail(token)
        return mapOf("verified" to true)
    }
}

@RestController
@RequestMapping("/api/v1/me")
class MeController(
    private val devices: DeviceService,
    private val preferences: PreferencesService,
    private val deletion: AccountDeletionService,
) {
    data class PushTokenRequest(val pushToken: String?)

    data class DeleteRequest(@field:NotBlank val password: String)

    @GetMapping("/devices")
    fun devices(): List<DeviceView> = devices.list(CurrentUser.id())

    @DeleteMapping("/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        @PathVariable id: UUID,
    ) = devices.revoke(CurrentUser.id(), id)

    @PutMapping("/devices/{id}/push-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun pushToken(
        @PathVariable id: UUID,
        @RequestBody request: PushTokenRequest,
    ) = devices.setPushToken(CurrentUser.id(), id, request.pushToken)

    @GetMapping("/preferences")
    fun preferences(): Preferences = preferences.get(CurrentUser.id())

    @PatchMapping("/preferences")
    fun updatePreferences(
        @RequestBody patch: PreferencesPatch,
    ): Preferences = preferences.update(CurrentUser.id(), patch)

    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @Valid @RequestBody request: DeleteRequest,
    ) = deletion.delete(CurrentUser.id(), request.password)
}
