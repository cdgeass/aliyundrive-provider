package io.github.cdgeass.aliyundriveprovider

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.cdgeass.AliyunpanClient
import io.github.cdgeass.aliyundriveprovider.ui.theme.AliyundriveProviderTheme
import io.github.cdgeass.model.GetUserResponse
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

sealed class UiState {
    object Initial : UiState()
    object Verified : UiState()
    object Authorized : UiState()
    data class Error(val message: String) : UiState()
}

private const val TAG = "AliyundriveProvider"

class MyViewModel(
    private val application: Application
) : ViewModel() {

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as Application
                MyViewModel(application)
            }
        }
    }

    private val client = AliyunpanClient()
    private val sharedPreferences: SharedPreferences by lazy {
        application.getSharedPreferences(TAG, Context.MODE_PRIVATE)
    }

    private val _refreshToken = mutableStateOf<String?>(null)
    val refreshToken: String?
        get() = _refreshToken.value

    private val _uiState = mutableStateOf<UiState>(UiState.Initial)
    val uiState: UiState
        get() = _uiState.value

    private val _user = mutableStateOf<GetUserResponse?>(null)
    val user: GetUserResponse?
        get() = _user.value


    init {
        val refreshToken = sharedPreferences.getString("refreshToken", null)
        if (refreshToken == null) {
            _uiState.value = UiState.Initial
        } else {
            _refreshToken.value = refreshToken

            var (authorization, expiredAt) = readAuthorization()
            if (authorization != null && System.currentTimeMillis() < expiredAt) {
                _uiState.value = UiState.Verified

                viewModelScope.launch {
                    try {
                        val user = getUser(authorization)

                        _user.value = user
                        _uiState.value = UiState.Authorized
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to init", e)
                        _uiState.value = UiState.Error(e.message ?: "Unknown error")
                    }
                }
            } else {
                viewModelScope.launch {
                    try {
                        val (authorization, expiredAt) = getAuthorization(refreshToken)

                        val user = getUser(authorization)

                        _user.value = user
                        _uiState.value = UiState.Authorized
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to init", e)
                        _uiState.value = UiState.Error(e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    fun readAuthorization(): Pair<String?, Long> {
        val authorization = sharedPreferences.getString("authorization", null)
        val expiredAt = sharedPreferences.getLong("expiredAt", 0L)
        return Pair(authorization, expiredAt)
    }

    suspend fun getAuthorization(refreshToken: String): Pair<String, Long> {
        try {
            val response = client.getAccessToken(refreshToken).await()

            val authorization = response.authorization
            val expiredAt = System.currentTimeMillis() + response.expiresIn() * 1000

            return Pair(authorization, expiredAt)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to get authorization", e)
            throw e
        }
    }

    suspend fun getUser(authorization: String): GetUserResponse {
        try {
            return client.getUser(authorization).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get user", e)
            throw e
        }
    }

    suspend fun updateRefreshToken(refreshToken: String) {
        try {
            val (authorization, _) = getAuthorization(refreshToken)
            sharedPreferences.edit {
                putString("refreshToken", refreshToken)
            }

            _refreshToken.value = refreshToken
            _uiState.value = UiState.Verified

            val user = getUser(authorization)
            _user.value = user
            _uiState.value = UiState.Authorized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update refreshToken", e)
            _uiState.value = UiState.Error(e.message ?: "Unknown error")
        }
    }

    fun clearRefreshToken() {
        sharedPreferences.edit {
            remove("refreshToken")
            remove("authorization")
            remove("expiredAt")
        }
        _refreshToken.value = null
        _uiState.value = UiState.Initial
        _user.value = null
    }
}

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val viewModel = ViewModelProvider(this, MyViewModel.Factory)[MyViewModel::class.java]
        setContent {
            AliyundriveProviderTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Text("Aliyundrive Provider")
                            }
                        )
                    }
                ) { innerPadding ->
                    SetRefreshToken(
                        modifier = Modifier.padding(innerPadding),
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
fun SetRefreshToken(modifier: Modifier, viewModel: MyViewModel) {
    var refreshTokenInput by remember { mutableStateOf(viewModel.refreshToken ?: "") }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (viewModel.uiState) {
            is UiState.Initial -> {
                OutlinedTextField(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    value = refreshTokenInput,
                    onValueChange = { refreshTokenInput = it },
                    label = {
                        Text("RefreshToken")
                    },
                    singleLine = true
                )
                Button(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.updateRefreshToken(refreshTokenInput)
                        }
                    }
                ) {
                    Text("确认")
                }
            }

            is UiState.Verified -> {
                Text("Refreshing token...")
            }

            is UiState.Authorized -> {
                val userInfo = viewModel.user
                if (userInfo != null) {
                    Text(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        text = "User ID: ${userInfo.userId}"
                    )
                    Text(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        text = "Nick Name: ${userInfo.nickName}"
                    )
                } else {
                    Text("Loading user info...")
                }
                Button(
                    onClick = {
                        viewModel.clearRefreshToken()
                    }
                ) {
                    Text("清除 RefreshToken")
                }
            }

            is UiState.Error -> {
                Text("Error: ${(viewModel.uiState as UiState.Error).message}")
                Button(
                    onClick = {
                        viewModel.clearRefreshToken()
                    }
                ) {
                    Text("清除 RefreshToken")
                }
            }
        }
    }
}