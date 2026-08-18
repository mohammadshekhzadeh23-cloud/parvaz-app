package com.example.vray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class OnboardingSlide(val title: String, val body: String)

private val slides = listOf(
    OnboardingSlide(
        "به پرواز خوش اومدی",
        "با چند ضربه به بهترین سرور وصل شو و مرور امن و سریع رو تجربه کن."
    ),
    OnboardingSlide(
        "افزودن سرور آسونه",
        "لینک سرور رو پیست کن، کد QR رو اسکن کن، یا لینک اشتراک بذار تا خودش سرورها رو بیاره."
    ),
    OnboardingSlide(
        "همیشه در دسترس",
        "اتصال خودکار، سوییچ بین سرورها در صورت قطعی، و ویجت روی صفحه اصلی برای دسترسی سریع."
    )
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                val slide = slides[page]
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(slide.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Text(slide.body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(slides.size) { index ->
                    val active = pagerState.currentPage == index
                    Box(
                        Modifier
                            .padding(4.dp)
                            .size(if (active) 10.dp else 8.dp)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(24.dp)) {
                if (pagerState.currentPage < slides.size - 1) {
                    TextButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("رد کردن") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("بعدی") }
                } else {
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("شروع کن") }
                }
            }
        }
    }
}
