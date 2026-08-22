package com.example.vray.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class InfoItem(val title: String, val body: String)
private data class InfoSection(val heading: String, val items: List<InfoItem>)

private val sections = listOf(
    InfoSection(
        "صفحه‌ی اصلی",
        listOf(
            InfoItem("دکمه‌ی اتصال", "سرور انتخاب‌شده رو وصل یا قطع می‌کنه. اولین بار، سیستم عامل یه تاییدیه‌ی «اجازه‌ی VPN» می‌خواد که باید تایید کنی."),
            InfoItem("اتصال سریع", "همه‌ی سرورهای Xray (VMess/VLESS/Trojan/SS) رو هم‌زمان پینگ می‌کنه و خودش بهترین (کم‌تاخیرترین) رو وصل می‌کنه. سرورهای WireGuard/OpenVPN توی این تست شرکت نمی‌کنن."),
            InfoItem("آمار آپلود/دانلود", "فقط وقتی به یه سرور Xray وصلی نشون داده می‌شه؛ برای WireGuard/OpenVPN فعلاً این آمار نیست."),
            InfoItem("Kill Switch فعاله", "این پیام فقط وقتی نشون داده می‌شه که تنظیم Kill Switch روشن باشه و اتصال با خطا مواجه بشه؛ یعنی به‌جای برگشتن به اینترنت باز، گوشی آفلاین می‌مونه تا وصل بشه.")
        )
    ),
    InfoSection(
        "لیست سرورها",
        listOf(
            InfoItem("پینگ همه", "به‌صورت هم‌زمان از همه‌ی سرورهای پشتیبانی‌شده (Xray) تست تاخیر می‌گیره."),
            InfoItem("جستجو", "وقتی چند سرور یا چند گروه داری ظاهر می‌شه؛ بر اساس اسم یا آدرس فیلتر می‌کنه."),
            InfoItem("زدن روی هدر یه گروه", "اون گروه رو باز یا بسته (جمع) می‌کنه — فقط ظاهریه، سرورها حذف نمی‌شن."),
            InfoItem("رادیو کنار هر سرور", "همون سرور رو برای اتصال بعدی انتخاب می‌کنه."),
            InfoItem("آیکون سرعت‌سنج", "تست پینگ تکی همون سرور. برای WireGuard/OpenVPN این آیکون نیست چون هنوز پشتیبانی نمی‌شه."),
            InfoItem("منوی سه‌نقطه", "ویرایش، اشتراک‌گذاری (فرستادن کانفیگ به یه نفر دیگه)، یا حذف همون سرور — حذف قبلش تاییدیه می‌خواد.")
        )
    ),
    InfoSection(
        "اشتراک‌ها (Subscription)",
        listOf(
            InfoItem("افزودن اشتراک جدید", "یه لینک اشتراک اضافه می‌کنه؛ سرورهاش خودکار دانلود و به یه گروه جدا اضافه می‌شن."),
            InfoItem("بروزرسانی", "سرورهای همون اشتراک رو با نسخه‌ی جدید عوض می‌کنه؛ سرورهای دستی یا اشتراک‌های دیگه دست‌نخورده می‌مونن."),
            InfoItem("تغییر نام", "فقط اسم نمایشی گروه رو عوض می‌کنه."),
            InfoItem("حذف اشتراک", "خودِ اشتراک و همه‌ی سرورهای اون گروه حذف می‌شن (تاییدیه می‌خواد)."),
            InfoItem("کارت روز/حجم باقی‌مانده", "اگه فروشنده‌ی اشتراک این اطلاعات رو بفرسته نشون داده می‌شه؛ دکمه‌ی 🔄 کنارش همون اشتراک رو بروزرسانی می‌کنه.")
        )
    ),
    InfoSection(
        "افزودن سرور (دکمه‌ی +)",
        listOf(
            InfoItem("تب لینک", "برای لینک‌های vmess/vless/trojan/ss — با اسکن QR یا چسباندن از کلیپ‌بورد هم پر می‌شه."),
            InfoItem("تب WireGuard", "متن کامل کانفیگ (شامل [Interface] و [Peer]) رو پیست کن یا فایل .conf رو انتخاب کن."),
            InfoItem("تب OpenVPN", "فایل یا متن .ovpn رو اضافه می‌کنه؛ توجه: اتصال واقعی OpenVPN فعلاً فعال نیست، فقط کانفیگ ذخیره می‌شه.")
        )
    ),
    InfoSection(
        "تنظیمات — پایه",
        listOf(
            InfoItem("پورت SOCKS / HTTP", "پورت‌های محلی که Xray روشون پروکسی می‌ده. تغییرش لازم نیست مگر تداخل پورت داشته باشی."),
            InfoItem("فعال‌سازی UDP", "خاموش = ترافیک UDP (مثل بازی یا تماس صوتی/تصویری) از تونل رد نمی‌شه، فقط TCP."),
            InfoItem("Mux (مالتی‌پلکس)", "روشن = چند اتصال روی یه کانال مشترک می‌ره، ممکنه سرعت اتصال‌های زیاد رو بهتر کنه ولی گاهی با بعضی سرورها ناسازگاره."),
            InfoItem("ترافیک شبکه محلی مستقیم بره", "روشن (پیش‌فرض) = دستگاه‌های شبکه‌ی خونه/دفتر (مثل پرینتر) از تونل رد نمی‌شن. خاموش = همه‌چیز از تونل رد می‌شه.")
        )
    ),
    InfoSection(
        "تنظیمات — اتصال هوشمند (فقط Xray)",
        listOf(
            InfoItem("اتصال مجدد خودکار", "روشن = اگه اتصال قطع بشه یا وای‌فای/دیتا عوض بشه، خودش دوباره تلاش می‌کنه (تا ۵ بار). خاموش = فقط دستی می‌تونی دوباره وصل کنی."),
            InfoItem("سوییچ خودکار به سرور دیگر", "روشن = اگه سرور انتخابی جواب نداد، خودش سرور بعدی لیست رو امتحان می‌کنه."),
            InfoItem("Kill Switch", "روشن = اگه اتصال قطع بشه و نتونه وصل بشه، اینترنت گوشی مسدود می‌مونه (به‌جای رفتن به اینترنت باز) تا خودت دستی قطعش کنی یا دوباره وصل بشه. خاموش = اگه اتصال قطع بشه، گوشی به اینترنت عادی برمی‌گرده.")
        )
    ),
    InfoSection(
        "تنظیمات — پیشرفته",
        listOf(
            InfoItem("اتصال خودکار هنگام باز شدن اپ", "روشن = هر بار اپ رو باز کنی و سروری قطع باشه، خودش وصل می‌شه."),
            InfoItem("اتصال خودکار بعد از روشن شدن گوشی", "روشن = بعد از ری‌استارت گوشی، بدون باز کردن اپ وصل می‌شه — به شرطی که قبلاً حداقل یک‌بار مجوز VPN رو داده باشی."),
            InfoItem("ظاهر برنامه", "روشن/تاریک/مطابق سیستم — فقط ظاهریه، رو عملکرد اثر نداره."),
            InfoItem("MTU", "اندازه‌ی بسته‌های شبکه. پیش‌فرض ۱۵۰۰ برای اکثر شبکه‌ها خوبه؛ اگه توی بعضی وای‌فای/دیتاها قطع‌وصل می‌شی، عدد رو کمتر کن (مثلاً ۱۴۰۰).")
        )
    ),
    InfoSection(
        "تنظیمات — مسیریابی و تانل اپ‌ها (فقط Xray)",
        listOf(
            InfoItem("همه ترافیک از تانل", "همه‌چیز از سرور خارجی رد می‌شه."),
            InfoItem("ایران/چین مستقیم", "سایت‌های داخلی همون کشور مستقیم (بدون تونل) باز می‌شن، فقط بقیه از تونل رد می‌شه — معمولاً سریع‌تره."),
            InfoItem("تانل کردن اپ‌ها", "می‌تونی انتخاب کنی همه‌ی اپ‌ها از تونل رد بشن، یا فقط چندتای خاص، یا همه به‌جز چندتای خاص.")
        )
    ),
    InfoSection(
        "پشتیبان‌گیری و پشتیبانی",
        listOf(
            InfoItem("خروجی گرفتن", "یه فایل شامل همه‌ی سرورها و اشتراک‌هات می‌سازه که می‌تونی جای امنی نگه داری یا به گوشی دیگه منتقل کنی."),
            InfoItem("بازیابی از فایل", "همون فایل پشتیبان رو برمی‌گردونه — سرورها/اشتراک‌های فعلی جایگزین می‌شن."),
            InfoItem("آیکون پشتیبانی (بالای صفحه)", "مستقیم آیدی تلگرام پشتیبانی رو نشون می‌ده.")
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("راهنما") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            sections.forEach { section ->
                Text(section.heading, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                section.items.forEach { item ->
                    Text(item.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        item.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Divider()
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
