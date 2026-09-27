# Mizanora (Android MVP)

Ek Kotlin Android app jo GitHub Actions se build hoti hai. Kya karti hai,
seedha:

1. Aap task type ya bolte hain ("open settings and turn on WiFi").
2. App ek screenshot leti hai (MediaProjection).
3. Screenshot + task Gemini ko bhejti hai.
4. Gemini EK step batata hai (tap/swipe/type/open app/...).
5. Accessibility Service woh step perform karti hai.
6. Yeh loop chalta hai jab tak Gemini "done" na bole (max 12 steps/task).

## Yeh kya NAHI hai (important)
- **Invisible nahi.** Jab bhi capture/automation chalega, Android ek
  persistent notification aur status-bar dot dikhayega. Yeh OS-level
  security guarantee hai, koi bhi app isko chhupa nahi sakti.
- **Real-time continuous video nahi.** Yeh on-demand screenshot loop hai,
  ek "live camera feed" nahi — battery/data bachane ke liye jaan-boojh kar.
- **Poori tarah tested nahi.** Is sandbox mein Android SDK/network nahi hai,
  isliye code yahan compile-test nahi ho saka. Asli build/test aapke GitHub
  repo mein Actions chalne par hoga.

## Setup (4 steps — API key ab GitHub mein nahi, app ke andar hi jaata hai)
1. Is folder ko apne GitHub repo mein push karein (root mein).
2. `main` branch par push karein — Actions tab mein build khud chalegi
   (GitHub secret ki zaroorat NAHI hai ab).
3. Build khatam hone par, Actions run ke "Artifacts" section se
   `mizanora-debug-apk` download karein, phone mein install karein
   (Unknown Sources allow karna hoga, kyunki yeh Play Store se nahi hai).
4. App kholein → sabse pehle apni Gemini API key paste karke **Save** karein
   ([aistudio.google.com/apikey](https://aistudio.google.com/apikey) se free
   mil jaati hai) — yeh phone par encrypted store hoti hai (Android Keystore
   ke through), sirf Gemini ko call karte waqt use hoti hai. Phir teeno
   buttons dabayein (Accessibility → Mic → Start) → task type/bol kar bhejein.

## "Keep watching" mode
Checkbox on karne se Mizanora har ~15 seconds (Settings se badal sakte hain —
`SecureConfig.setWatchIntervalSec`) screen dekhti rahegi aur sirf tabhi bolegi
jab kuch dhyaan dene layak ho (error, naya message, warning) — normal screen
par chup rehti hai. **Yeh har check par ek real Gemini API call karta hai —
zyada der on rakhne se API cost aur battery dono badhenge.** Bade tasks ab
default 40 steps tak chal sakte hain (`SecureConfig.setMaxSteps` se aur
badha sakte hain).

## Safety — please read before pointing this at real apps
Yeh khud tap/type/swipe karta hai bina har step confirm kiye. Banking,
payments, ya kisi bhi "irreversible" action wale app ke saath abhi is MVP
ko mat chalayein jab tak aap khud dekh na rahe hon ke woh kya kar raha hai.

## Jo agla kaam ho sakta hai (didn't build this time)
- Wake-word / hands-free "hey Mizanora" trigger on phone.
- Multi-step planning se pehle ek "confirm before risky action" screen
  (abhi yeh directly tap/type kar deta hai — banking/payment apps ke saath
  bahut saावdhaani se test karein).
- Gemini Live (real bidirectional audio) — abhi request/response loop hai.
