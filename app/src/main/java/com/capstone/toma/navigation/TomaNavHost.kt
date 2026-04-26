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
import kotlinx.coroutines.delay
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
import com.capstone.toma.ui.screen.RecipeDetailScreen
import androidx.navigation.navArgument
import androidx.navigation.NavType
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

    val chatViewModel: ChatViewModel = viewModel()
    val chatUiState by chatViewModel.uiState.collectAsState()

    val voiceViewModel: VoiceViewModel = viewModel()
    val voiceUiState by voiceViewModel.uiState.collectAsState()
    val voiceResult by voiceViewModel.searchResult.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            chatViewModel.resetChat() // 기존 채팅 초기화
            navController.navigate(TomaDestination.Chat.route)
            chatViewModel.startLinkAnalysis(
                userDisplay = "사진으로 레시피 찾기",
                initialAiText = "사진을 분석하고 있어요. 잠시만 기다려 주세요... 📸"
            ) { _ ->
                val openAi = com.capstone.toma.OpenAiManager()
                openAi.analyzeRecipeImageSuspend(context, uri.toString())
            }
        } else {
            homeViewModel.showError("이미지를 선택하지 않았어요.")
        }
    }

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
                        chatViewModel.resetChat() // 기존 채팅 초기화
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
                        val isBlog = link.contains("blog.naver.com") || link.contains("tistory.com")
                        
                        if (isYoutube || isBlog) {
                            val webPageManager = WebPageManager()
                            chatViewModel.resetChat() // 기존 채팅 초기화
                            navController.navigate(TomaDestination.Chat.route)
                            
                            val displayMsg = if(isYoutube) "유튜브 분석 중: $link" else "블로그 분석 중: $link"

                            chatViewModel.startLinkAnalysis(
                                userDisplay = displayMsg,
                                initialAiText = "[1/2] 페이지 본문을 읽어오고 있어요... 📄"
                            ) { updateStatus ->
                                // Coroutine scope 내에서 실행됨
                                
                                // 1단계: 웹 데이터 추출 (suspend 함수 사용)
                                val (t, d, img) = webPageManager.fetchPageInfoSuspend(link)
                                
                                if (t == null && d?.contains("실패") == true) {
                                    homeViewModel.showError(d, isDialog = true)
                                    return@startLinkAnalysis VoiceRequestResult.Error(d)
                                }

                                // 2단계: 강화된 전문가 AI 분석 (사용자 지침 반영)
                                updateStatus("[2/2] 전문가 AI가 실전 정보를 추출 중이에요... ✨")
                                delay(600)
                                
                                val hiddenPrompt = """
                                    [전문가 레시피 분석가 모드]
                                    입력 링크: $link
                                    이미지 URL: ${img ?: "없음"}
                                    페이지 제목: ${t ?: "제목 없음"}
                                    추출된 본문: ${d ?: "본문 없음"}
                                    
                                    [필수 지침]
                                    1. 절대 답변에 "[요리명]"이나 "[추출된 재료 요약]" 같은 대괄호 예시를 그대로 출력하지 마세요.
                                    2. 위 '추출된 본문' 데이터에서 실제 요리 정보를 찾아 구체적으로 작성하세요.
                                    3. 답변 형식:
                                       - 텍스트: "분석을 완료했어요! [실제 요리명] 레시피가 맞나요? 
                                         주요 재료: [실제 재료들 리스트]
                                         특징: [이 레시피만의 장점이나 특징]
                                         
                                         이 레시피로 조리 안내를 시작할까요?"
                                       - JSON: 하단에 반드시 아래 형식을 채워서 포함하세요.
                                         {
                                           "type": "recipe_search",
                                           "keyword": "실제 요리명",
                                           "recipe_data": {
                                             "title": "실제 요리명",
                                             "ingredients": ["실제 재료1", "실제 재료2"],
                                             "steps": ["실제 조리 1단계", "실제 조리 2단계"],
                                             "difficulty": "보통",
                                             "time": "예상 시간",
                                             "image_url": "${img ?: ""}"
                                           }
                                         }
                                """.trimIndent()

                                val openAi = com.capstone.toma.OpenAiManager()
                                val history = chatViewModel.uiState.value.messages.map { it.text to it.isUser }
                                
                                // suspend 버전 API 호출
                                openAi.processChatRequestSuspend(hiddenPrompt, history)
                            }
                            homeViewModel.updateYoutubeLink("")
                        } else {
                            homeViewModel.showError("유튜브 또는 네이버/티스토리 블로그 링크만 가능합니다.")
                        }
                    }
                },
                onPhotoScanClick = {
                    imagePickerLauncher.launch("image/*")
                },
                onRecentItemClick = { itemId ->
                    // [연결] 최근 항목 클릭 시 해당 제목으로 즉시 AI 채팅/분석 시작
                    val item = homeUiState.recentItems.find { it.id == itemId }
                    item?.let {
                        chatViewModel.sendMessage(it.title)
                        navController.navigate(TomaDestination.Chat.route)
                    }
                },
                onRecentMoreClick = {
                    // [연결] 더보기 클릭 시 저장소로 이동
                    navController.navigateSingleTop(TomaDestination.RecipeStorage.route)
                },
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
                },
                onErrorDismiss = homeViewModel::clearError
            )
        }

        composable(TomaDestination.RecipeStorage.route) {
            RecipeStorageScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(TomaDestination.VoiceGuide.route) {
            // VoiceViewModel에서 결과가 나오면 화면 이동
            LaunchedEffect(voiceResult) {
                voiceResult?.let { result ->
                    if (result.requestType == "recipe_search") {
                        // 레시피 검색인 경우 바로 상세 화면으로 이동
                        navController.navigate(TomaDestination.RecipeDetail.createRoute(result.keyword)) {
                            popUpTo(TomaDestination.VoiceGuide.route) { inclusive = true }
                        }
                    } else {
                        // 그 외(추천 등)는 채팅 화면으로 이동
                        chatViewModel.addInitialMessages(result.userQuery, result.responseMessage)
                        navController.navigate(TomaDestination.Chat.route) {
                            popUpTo(TomaDestination.VoiceGuide.route) { inclusive = true }
                        }
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
            val navEvent by chatViewModel.navigationEvent.collectAsState()
            val errorEvent by chatViewModel.errorEvent.collectAsState()
            val context = androidx.compose.ui.platform.LocalContext.current
            
            LaunchedEffect(navEvent) {
                navEvent?.let { (keyword, recipeData) ->
                    navController.navigate(TomaDestination.RecipeDetail.createRoute(keyword, recipeData))
                    chatViewModel.clearNavigationEvent()
                }
            }

            LaunchedEffect(errorEvent) {
                errorEvent?.let { message ->
                    android.widget.Toast.makeText(context, "분석 실패: $message", android.widget.Toast.LENGTH_SHORT).show()
                    chatViewModel.clearErrorEvent()
                }
            }

            AiChatScreen(
                uiState = chatUiState,
                onBackClick = { navController.popBackStack() },
                onInputTextChange = chatViewModel::onInputTextChange,
                onSendMessage = chatViewModel::sendMessage,
                onMicClick = {
                    // 채팅창에서도 음성 인식을 쓰고 싶다면 VoiceGuide로 이동하거나 별도 로직 추가
                    navController.navigate(TomaDestination.VoiceGuide.route)
                },
                onErrorDismiss = chatViewModel::clearErrorEvent
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

        composable(
            route = TomaDestination.RecipeDetail.route,
            arguments = listOf(
                navArgument("keyword") { type = NavType.StringType },
                navArgument("recipeData") { 
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val keyword = backStackEntry.arguments?.getString("keyword") ?: ""
            val recipeData = backStackEntry.arguments?.getString("recipeData")
            RecipeDetailScreen(
                keyword = keyword,
                recipeDataJson = recipeData,
                onBackClick = { navController.popBackStack() }
            )
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