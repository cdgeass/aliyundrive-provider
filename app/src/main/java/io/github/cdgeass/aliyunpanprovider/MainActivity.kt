package io.github.cdgeass.aliyunpanprovider

import android.app.Application
import android.content.Context
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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.cdgeass.AliyunpanClient
import io.github.cdgeass.aliyunpanprovider.ui.theme.AliyunpanProviderTheme
import io.github.cdgeass.model.GetUserResponse
import kotlinx.coroutines.launch

sealed class UiState {
    object Initial : UiState()
    object Verified : UiState()
    object Authorized : UiState()
    data class Error(val message: String) : UiState()
}

class MyViewModel(
    private val application: Application
) : ViewModel() {
    private val _refreshToken = mutableStateOf<String?>(null)
    val refreshToken: String?
        get() = _refreshToken.value

    private val _uiState = mutableStateOf<UiState>(UiState.Initial)
    val uiState: UiState
        get() = _uiState.value

    private val _user = mutableStateOf<GetUserResponse?>(null)
    val user: GetUserResponse?
        get() = _user.value

    private val client = AliyunpanClient()

    init {
        _refreshToken.value = getRefreshToken()
        val authorization = getAuthorization()
        if (_refreshToken.value != null && authorization != null) {
            _uiState.value = UiState.Verified
            getUserInfo()
        } else {
            _uiState.value = UiState.Initial
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as Application
                MyViewModel(application)
            }
        }
    }

    fun getRefreshToken(): String? {
        return application.getSharedPreferences("AliyunpanProvider", Context.MODE_PRIVATE)
            .getString(
                "refreshToken",
                null
            )
    }

    fun getAuthorization(): String? {
        return application.getSharedPreferences("AliyunpanProvider", Context.MODE_PRIVATE)
            .getString(
                "authorization",
                null
            )
    }

    fun setRefreshToken(refreshToken: String) {
        try {
            val getAccessTokenFuture = client.getAccessToken(refreshToken)
            val getAccessTokenResponse = getAccessTokenFuture.get()
            val authorization = getAccessTokenResponse.authorization

            application.getSharedPreferences("AliyunpanProvider", Context.MODE_PRIVATE).edit {
                putString(
                    "refreshToken",
                    refreshToken
                )
                putString(
                    "authorization",
                    authorization
                )
            }
            _refreshToken.value = refreshToken
            _uiState.value = UiState.Verified
            getUserInfo()
        } catch (e: Exception) {
            Log.e("AliyunpanProvider", "setRefreshToken", e)
            _uiState.value = UiState.Error(e.message ?: "Unknown error")
        }
    }

    fun clearRefreshToken() {
        application.getSharedPreferences("AliyunpanProvider", Context.MODE_PRIVATE).edit {
            remove("refreshToken")
            remove("authorization")
        }
        _refreshToken.value = null
        _uiState.value = UiState.Initial
        _user.value = null
    }

    fun getUserInfo() {
        val authorization = getAuthorization()
        if (authorization != null) {
            try {
                val getUserFuture = client.getUser(authorization)
                val userInfoResponse = getUserFuture.get()
                _user.value = userInfoResponse
                _uiState.value = UiState.Authorized
            } catch (e: Exception) {
                Log.e("AliyunpanProvider", "getUserInfo", e)
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
                clearRefreshToken() // Clear token if user info fetch fails
            }
        } else {
            _uiState.value = UiState.Initial
        }
    }
}

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val viewModel = ViewModelProvider(this, MyViewModel.Factory)[MyViewModel::class.java]
        setContent {
            AliyunpanProviderTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Text("Aliyunpan Provider")
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
                            viewModel.setRefreshToken(refreshTokenInput)
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
                    Text("User ID: ${userInfo.userId}")
                    Text("Nick Name: ${userInfo.nickName}")
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