package com.capstone.toma.navigation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.capstone.toma.ui.screen.AiChatScreen
import com.capstone.toma.VoiceRequestResult
import com.capstone.toma.ui.screen.ContactUsScreen
import com.capstone.toma.ui.screen.CustomerCenterScreen
import com.capstone.toma.ui.screen.EmailSettingScreen
import com.capstone.toma.ui.screen.PushSettingScreen
import com.capstone.toma.ui.screen.SettingsScreen
import com.capstone.toma.ui.screen.PrivacyPolicyScreen
import com.capstone.toma.viewmodel.VoiceViewModel
import com.capstone.toma.viewmodel.HomeViewModel
import com.capstone.toma.viewmodel.ChatViewModel
import com.capstone.toma.ui.screen.RecipeStorageScreen
import com.capstone.toma.WebPageManager
import com.capstone.toma.ui.screen.TomaHomeScreen
import com.capstone.toma.ui.screen.VoiceGuideScreen
import androidx.compose.runtime.LaunchedEffect

private val voiceSuggestions = listOf(
    "메뉴 추천해줘",
    "재료로 요리 찾아줘",
    "간단한 레시피 알려줘",
    "빠른 요리 찾아줘",
    "쉬운 요리 추천해줘"
)

@Composable
fun TomaNavHost(
    navController: NavHostController = rememberNavController()
) {
    val homeViewModel: HomeViewModel = viewModel()
    val homeUiState by homeViewModel.uiState.collectAsState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            homeViewModel.onImageSelected(uri.toString())
        } else {
            homeViewModel.showError("이미지를 선택하지 않았어요.")
        }
    }

    val voiceViewModel: VoiceViewModel = viewModel()
    val voiceUiState by voiceViewModel.uiState.collectAsState()
    val voiceResult by voiceViewModel.searchResult.collectAsState()

    val chatViewModel: ChatViewModel = viewModel()
    val chatUiState by chatViewModel.uiState.collectAsState()

    NavHost(
        navController = navController,
        startDestination = TomaDestination.Home.route
    ) {
        composable(TomaDestination.Home.route) {
            TomaHomeScreen(
                uiState = homeUiState,
                onSearchQueryChange = homeViewModel::updateSearchQuery,
                onSearchSubmit = {
                    val query = homeUiState.searchQuery
                    if (query.isNotBlank()) {
                        chatViewModel.sendMessage(query)
                        navController.navigate(TomaDestination.Chat.route)
                        homeViewModel.updateSearchQuery("")
                    }
                },
                onMicClick = {
                    navController.navigateSingleTop(TomaDestination.VoiceGuide.route)
                },
                onYoutubeLinkChange = homeViewModel::updateYoutubeLink,
                onYoutubeSubmit = {
                    val link = homeUiState.youtubeLink
                    if (link.isNotBlank()) {
                        val isYoutube = link.contains("youtube.com") || link.contains("youtu.be")
                        
                        if (!isYoutube) {
                            homeViewModel.showError("현재 유튜브 링크 분석만 지원합니다.")
                            return@onYoutubeSubmit
                        }

                        val webPageManager = WebPageManager()
                        navController.navigate(TomaDestination.Chat.route)
                        
                        // 1단계: 분석 시작 메시지 띄우기
                        chatViewModel.startLinkAnalysis(
                            userDisplay = "유튜브 레시피 분석해줘: $link",
                            initialAiText = "[1/2] 영상 정보를 추출하고 있어요... 🔍"
                        ) { updateStatus ->
                            
                            // 정보 추출 (Coroutine 대기)
                            var t: String? = null
                            var d: String? = null
                            var done = false
                            webPageManager.fetchPageInfo(link) { title, desc ->
                                t = title
                                d = desc
                                done = true
                            }
                            // 데이터가 올 때까지 잠시 대기
                            while(!done) { kotlinx.coroutines.delay(200) }

                            // 2단계: AI 분석 단계로 상태 업데이트
                            updateStatus("[2/2] 레시피를 분석하고 있어요... 🍳")
                            kotlinx.coroutines.delay(500) // 사용자 인지를 위한 짧은 대기
                            
                            val hiddenPrompt = """
                                [유튜브 레시피 분석 전용]
                                링크: $link
                                제목: ${t ?: "알 수 없음"}
                                설명: ${d ?: "설명 없음"}
                                
                                위 정보를 바탕으로 요리명을 정확히 추출하고, 
                                반드시 "분석을 완료했어요! [요리명] 레시피 안내를 시작할까요?" 문구와 
                                JSON { "type": "recipe_search", "keyword": "요리명" }을 포함해줘.
                            """.trimIndent()

                            // OpenAI 실제 호출 및 결과 반환
                            var finalResult: VoiceRequestResult = VoiceRequestResult.Error("분석 중 오류 발생")
                            var apiDone = false
                            val openAi = com.capstone.toma.OpenAiManager()
                            val history = chatViewModel.uiState.value.messages.map { it.text to it.isUser }
                            
                            openAi.processChatRequest(hiddenPrompt, history) {
                                finalResult = it
                                apiDone = true
                            }
                            while(!apiDone) { kotlinx.coroutines.delay(100) }
                            
                            finalResult
                        }

                        homeViewModel.updateYoutubeLink("")
                    }
                },
                onPhotoScanClick = {
                    imagePickerLauncher.launch("image/*")
                },
                onRecentItemClick = { itemId ->
                    homeViewModel.selectRecentItem(itemId)
                },
                onRecentMoreClick = {},
                onHomeClick = {
                    navController.navigateSingleTop(TomaDestination.Home.route)
                },
                onStorageClick = {
                    navController.navigateSingleTop(TomaDestination.RecipeStorage.route)
                },
                onSettingsClick = {
                    navController.navigateSingleTop(TomaDestination.Settings.route)
                },
                onPrivacyPolicyClick = {
                    navController.navigateSingleTop(TomaDestination.PrivacyPolicy.route)
                }
            )
        }

        composable(TomaDestination.RecipeStorage.route) {
            RecipeStorageScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(TomaDestination.VoiceGuide.route) {
            // VoiceViewModel에서 결과가 나오면 채팅 화면으로 이동
            LaunchedEffect(voiceResult) {
                voiceResult?.let { result ->
                    chatViewModel.addInitialMessages(result.userQuery, result.responseMessage)
                    navController.navigate(TomaDestination.Chat.route) {
                        popUpTo(TomaDestination.VoiceGuide.route) { inclusive = true }
                    }
                    voiceViewModel.reset()
                }
            }

            VoiceGuideScreen(
                uiState = voiceUiState,
                suggestions = voiceSuggestions,
                onMicClick = {
                    voiceViewModel.onMicClick()
                },
                onSuggestionClick = { text ->
                    voiceViewModel.onSuggestionClick(text)
                }
            )
        }

        composable(TomaDestination.Chat.route) {
            AiChatScreen(
                uiState = chatUiState,
                onBackClick = { navController.popBackStack() },
                onInputTextChange = chatViewModel::onInputTextChange,
                onSendMessage = chatViewModel::sendMessage,
                onMicClick = {
                    // 채팅창에서도 음성 인식을 쓰고 싶다면 VoiceGuide로 이동하거나 별도 로직 추가
                    navController.navigate(TomaDestination.VoiceGuide.route)
                }
            )
        }

        composable(TomaDestination.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onPushClick = { navController.navigate(TomaDestination.PushSetting.route) },
                onEmailClick = { navController.navigate(TomaDestination.EmailSetting.route) },
                onCustomerCenterClick = { navController.navigate(TomaDestination.CustomerCenter.route) },
                onContactClick = { navController.navigate(TomaDestination.ContactUs.route) }
            )
        }

        composable(TomaDestination.PushSetting.route) {
            PushSettingScreen(onBackClick = { navController.popBackStack() })
        }

        composable(TomaDestination.EmailSetting.route) {
            EmailSettingScreen(onBackClick = { navController.popBackStack() })
        }

        composable(TomaDestination.CustomerCenter.route) {
            CustomerCenterScreen(onBackClick = { navController.popBackStack() })
        }

        composable(TomaDestination.ContactUs.route) {
            ContactUsScreen(onBackClick = { navController.popBackStack() })
        }

        // 🌟 [추가] NavHost에 개인정보 처리방침 화면 목적지 등록!
        composable(TomaDestination.PrivacyPolicy.route) {
            PrivacyPolicyScreen(onBackClick = { navController.popBackStack() })
        }
    }
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
    }
}