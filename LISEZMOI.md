# Scancounter (Android)

Application Android native de comptage de stock : scan de codes-barres, lecture d'etiquettes (OCR), liste des articles avec quantites, import et export Excel (.xlsx) et CSV. Tout fonctionne hors ligne ; les donnees restent sur le telephone.

C'est le portage natif de la PWA [Scancounter-pwa](https://github.com/appScanpocket/Scancounter-pwa) (v0.18).

## Contenu du depot

- `app/` : code de l'application (Kotlin, vues classiques, ML Kit).
- Fichiers Gradle a la racine : `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/`.

## Compiler

1. Ouvrir ce dossier dans Android Studio.
2. Laisser Gradle se synchroniser (Sync).
3. Build > Build APK(s). L'APK se trouve dans `app/build/outputs/apk/debug/`.

## Fonctions

- Saisie : scan en direct (lecteur de codes Google, necessite Google Play Services), scan depuis une photo, lecture d'une etiquette (OCR), quantite, signalement d'un code deja present (additionner ou remplacer).
- Liste : consultation, modification, suppression.
- Outils : import .xlsx / .csv, export .xlsx, partage CSV.
