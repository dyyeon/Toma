package com.capstone.toma.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.capstone.toma.ui.screen.AiChatScreen
import com.capstone.toma.ui.screen.ContactUsScreen
import com.capstone.toma.ui.screen.CustomerCenterScreen
import com.capstone.toma.ui.screen.EmailSettingScreen
import com.capstone.toma.ui.screen.PrivacyPolicyScreen
import com.capstone.toma.ui.screen.PushSettingScreen
import com.capstone.toma.ui.screen.RecentHistoryScreen
import com.capstone.toma.ui.screen.RecipeConfirmScreen
import com.capstone.toma.ui.screen.RecipeDetailScreen
import com.capstone.toma.ui.screen.RecipeStorageScreen
import com.capstone.toma.ui.screen.SettingsScreen
import com.capstone.toma.ui.screen.TomaHomeScreen
import com.capstone.toma.ui.screen.VoiceGuideScreen
import com.capstone.toma.viewmodel.ChatNavigationEvent
import com.capstone.toma.viewmodel.ChatViewModel
import com.capstone.toma.viewmodel.HomeViewModel
import com.capstone.toma.viewmodel.VoiceViewModel
import com.capstone.toma.WebPageManager
import com.capstone.toma.VoiceRequestResult
import kotlinx.coroutines.delay

private val voiceSuggestions = listOf(
    "메뉴 추천해줘",
    "재료로 요리 찾아줘",
    "간단한 레시피 알려줘"
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

    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = TomaDestination.Home.route
    ) {
        composable(TomaDestination.Home.route) {
            LaunchedEffect(Unit) {
                homeViewModel.refreshRecentItems()
            }

            TomaHomeScreen(
                uiState = homeUiState,

                onSearchQueryChange = homeViewModel::updateSearchQuery,

                onSearchSubmit = {
                    val query = homeUiState.searchQuery
                    if (query.isNotBlank()) {
                        chatViewModel.resetChat()
                        chatViewModel.sendMessage(query)
                        navController.navigate(TomaDestination.Chat.route)
                        homeViewModel.updateSearchQuery("")
                    }
                },

                onMicClick = {
                    navController.navigateSingleTop(TomaDestination.VoiceGuide.route)
                },
                onLinkChange = homeViewModel::updateRecipeLink,

                onLinkSubmit = {
                    val link = homeUiState.recipeLink

                    if (link.isNotBlank()) {
                        val isYoutube = link.contains("youtube.com") || link.contains("youtu.be")
                        val isBlog = link.contains("naver.com") || link.contains("tistory.com")

                        if (isYoutube || isBlog) {
                            val webPageManager = WebPageManager()

                            chatViewModel.resetChat()
                            navController.navigate(TomaDestination.Chat.route)

                            val displayMsg =
                                if (isYoutube) "유튜브 분석 중: $link"
                                else "웹 레시피 분석 중: $link"

                            chatViewModel.startLinkAnalysis(
                                userDisplay = displayMsg,
                                initialAiText = "[1/2] 페이지 본문을 읽어오고 있어요... 📄"
                            ) { updateStatus ->

                                val (t, d, img) = webPageManager.fetchPageInfoSuspend(link)

                                if (t == null && d?.contains("실패") == true) {
                                    homeViewModel.showError(d, isDialog = true)
                                    return@startLinkAnalysis VoiceRequestResult.Error(d)
                                }

                                updateStatus("[2/2] AI가 레시피를 정리 중이에요... ✨")
                                delay(600)

                                val openAi = com.capstone.toma.OpenAiManager()
                                val history = chatViewModel.uiState.value.messages.map { it.text to it.isUser }

                                val hiddenPrompt = """
                                    [전문가 레시피 분석가 모드]
                                    입력 링크: $link
                                    이미지 URL: ${img ?: "없음"}
                                    페이지 제목: ${t ?: "제목 없음"}
                                    추출된 본문: ${d ?: "본문 없음"}
                                
                                    지침:
                                    1. 사용자의 안부나 잡담은 짧게 응대하고, 본론인 '레시피 분석'에 집중하세요.
                                    2. 본문에서 인사말, 후기, 광고, 협찬 문구는 제거하고 실제 조리 정보만 추출하세요.
                                    3. 유튜브 링크라면 자막/본문에서 조리 순서와 재료를 중심으로 정리하세요.
                                    4. 웹페이지 링크라면 본문에서 재료, 양념, 조리 단계, 조리 시간을 중심으로 정리하세요.
                                    5. 출력 형식:
                                       - 텍스트:
                                         "분석을 완료했어요! [요리명]이(가) 맞나요?
                                
                                         주요 재료: [추출된 재료 요약]
                                         이 레시피의 특징: [예: 초간단 버전, 매콤한 맛 강조 등]
                                
                                         이 레시피로 안내를 시작할까요?"
                                
                                       - JSON: 반드시 하단에 아래 형식을 포함하세요.
                                         {
                                           "type": "recipe_search",
                                           "keyword": "요리명",
                                           "recipe_data": {
                                             "title": "요리명",
                                             "ingredients": ["재료1", "재료2"],
                                             "steps": ["1단계", "2단계"],
                                             "difficulty": "보통",
                                             "time": "20분",
                                             "image_url": "${img ?: ""}"
                                           }
                                         }
                                """.trimIndent()

                                openAi.processChatRequestSuspend(hiddenPrompt, history)
                            }

                            homeViewModel.updateRecipeLink("")
                        } else {
                            homeViewModel.showError("지원되지 않는 링크입니다.")
                        }
                    }
                },

                onPhotoScanClick = {
                    homeViewModel.showError("이미지 분석 기능은 아직 다시 연결하지 않았습니다.", isDialog = true)
                },

                onRecentItemClick = { itemId ->
                    val item = homeUiState.recentItems.find { it.id == itemId }
                    item?.let {
                        navController.navigate(
                            TomaDestination.RecipeDetail.createRoute(it.title, it.recipeDataJson)
                        )
                    }
                },

                onRecentMoreClick = {
                    navController.navigateSingleTop(TomaDestination.RecentHistory.route)
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

        composable(TomaDestination.RecentHistory.route) {
            LaunchedEffect(Unit) {
                homeViewModel.refreshRecentItems()
            }

            RecentHistoryScreen(
                items = homeUiState.recentItems,
                onBackClick = { navController.popBackStack() },
                onItemClick = { item ->
                    navController.navigate(
                        TomaDestination.RecipeDetail.createRoute(item.title, item.recipeDataJson)
                    )
                }
            )
        }

        composable(TomaDestination.VoiceGuide.route) {
            LaunchedEffect(voiceResult) {
                voiceResult?.let { result ->
                    if (result.requestType == "recipe_search") {
                        navController.navigate(TomaDestination.RecipeConfirm.createRoute(result.keyword, null)) {
                            popUpTo(TomaDestination.VoiceGuide.route) { inclusive = true }
                        }
                    } else {
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

                onMicClick = { voiceViewModel.onMicClick() },
                onSuggestionClick = { text -> voiceViewModel.onSuggestionClick(text) }
            )
        }

        composable(TomaDestination.Chat.route) {
            val navEvent by chatViewModel.navigationEvent.collectAsState()
            val errorEvent by chatViewModel.errorEvent.collectAsState()

            LaunchedEffect(navEvent) {
                when (val event = navEvent) {
                    is ChatNavigationEvent.ToConfirm -> {
                        navController.navigate(
                            TomaDestination.RecipeConfirm.createRoute(
                                event.keyword,
                                event.recipeData
                            )
                        )
                        chatViewModel.clearNavigationEvent()
                    }
                    is ChatNavigationEvent.ToDetail -> {
                        navController.navigate(
                            TomaDestination.RecipeDetail.createRoute(
                                event.keyword,
                                event.recipeData
                            )
                        )
                        chatViewModel.clearNavigationEvent()
                    }
                    null -> Unit
                }
            }

            LaunchedEffect(errorEvent) {
                errorEvent?.let { message ->
                    android.widget.Toast.makeText(
                        context,
                        "분석 실패: $message",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    chatViewModel.clearErrorEvent()
                }
            }

            AiChatScreen(
                uiState = chatUiState,
                onBackClick = { navController.popBackStack() },
                onInputTextChange = chatViewModel::onInputTextChange,
                onSendMessage = chatViewModel::sendMessage,
                onMicClick = {
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
            route = TomaDestination.RecipeConfirm.route,
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
            RecipeConfirmScreen(
                keyword = keyword,
                recipeDataJson = recipeData,
                onBackClick = { navController.popBackStack() },
                onConfirmClick = {
                    homeViewModel.saveRecentRecipe(
                        keyword = keyword,
                        recipeDataJson = recipeData
                    )
                    navController.navigate(TomaDestination.RecipeDetail.createRoute(keyword, recipeData)) {
                        popUpTo(TomaDestination.RecipeConfirm.route) { inclusive = true }
                    }
                },
                onRejectClick = { navController.popBackStack() }
            )
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
