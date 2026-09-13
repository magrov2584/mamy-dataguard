# MamyDataGuard

Pare-feu **sans root** pour Android, basé sur l'API officielle `VpnService`.
Bloque l'accès Internet application par application, séparément pour le
**Wi-Fi** et la **Data Mobile**, sans qu'aucune donnée ne quitte l'appareil.

## 1. Ouvrir le projet dans Android Studio

1. `Fichier > Ouvrir` puis sélectionner le dossier `MamyDataGuard`.
2. Ce projet ne contient **pas** le wrapper Gradle (`gradlew` + `gradle-wrapper.jar`)
   car il n'a pas pu être téléchargé dans cet environnement. Deux options :
   - Le plus simple : Android Studio propose automatiquement de **générer le
     wrapper Gradle manquant** à l'ouverture ("Migrate to Gradle wrapper") — accepter.
   - Ou en ligne de commande, si vous avez déjà Gradle installé :
     ```
     gradle wrapper --gradle-version 8.7
     ```
3. Laisser Android Studio synchroniser (`Sync Now`).
4. Lancer sur un appareil réel ou un émulateur (**API 26 minimum**, recommandé API 29+
   pour l'identification de l'app bloquée dans les notifications, voir plus bas).

## 2. Architecture (correspond au cahier des charges)

| Composant demandé | Fichier |
|---|---|
| `VpnService` (tunnel local) | `FirewallVpnService.kt` |
| `ConnectivityManager` (Wi-Fi vs Data) | `FirewallVpnService.kt` (`isOnWifi`/`isOnMobile` + `NetworkCallback`) |
| `PackageManager` (liste des apps, icônes, UID) | `MainActivity.kt` (`loadInstalledApps`) |
| Moteur de filtrage de paquets | `IpPacketParser.kt` + boucle de lecture dans `FirewallVpnService.kt` |
| Persistance des règles par app | `PrefsManager.kt` (SharedPreferences) |
| UI liste + switches Wi-Fi/Data | `MainActivity.kt`, `AppListAdapter.kt`, `item_app.xml` |
| Notification 1ère tentative de connexion | `NotificationHelper.notifyBlockedAttempt` |
| Service premier plan + notification permanente | `NotificationHelper.buildServiceNotification` |
| Exclusion des optimisations batterie | `MainActivity.requestIgnoreBatteryOptimizations` |
| Redémarrage auto après reboot | `BootReceiver.kt` |

## 3. Comment fonctionne le blocage (important à comprendre)

Ce squelette utilise la technique **"tunnel blackhole"**, qui est la façon la plus
simple et robuste d'implémenter un blocage total d'accès réseau par app sans
écrire de moteur NAT complet en C/NDK :

- Seules les applications à **bloquer** pour le réseau actif (Wi-Fi ou Mobile)
  sont ajoutées au tunnel via `Builder.addAllowedApplication()`.
- Toutes les autres apps **bypassent automatiquement** le VPN (comportement natif
  d'Android dès qu'on utilise `addAllowedApplication`) et gardent un accès direct.
- Les paquets des apps bloquées entrent dans le tunnel mais **ne sont jamais
  réémis** vers l'interface réseau réelle → c'est le blocage effectif (timeout
  côté app bloquée).
- Le service lit quand même chaque paquet entrant pour en extraire l'UID
  propriétaire (via `ConnectivityManager.getConnectionOwnerUid`, **API 29+**)
  et déclencher une notification "AppX a tenté de se connecter".
- Quand la liste des apps à bloquer change (règle modifiée, changement Wi-Fi ↔
  Data), le tunnel est entièrement reconstruit (`rebuildTunnel()`).

**Limite connue** : sur Android 8/9 (API 26-28), le blocage fonctionne toujours,
mais la notification ne pourra pas nommer l'application responsable (l'API
`getConnectionOwnerUid` n'existe pas encore).

## 4. Pistes d'évolution (hors périmètre de ce squelette)

- **Moteur NDK complet** (façon NetGuard) : implémenter un vrai relai
  TCP/UDP en C/C++ pour du filtrage par domaine/IP plutôt que du tout-ou-rien
  par app, avec journalisation détaillée du trafic autorisé.
- **Liste blanche des services système critiques** : avant d'autoriser le
  blocage d'une app système (`isSystemApp`), afficher un avertissement
  explicite (déjà exposé dans `AppInfo.isSystemApp`, à exploiter côté UI).
- **Recherche/filtre** dans la liste d'apps, tri par "récemment bloquées".
- **Export/import des règles** (JSON) pour sauvegarder une configuration.
- **Optimisation batterie du moteur de lecture** : regrouper les lectures,
  éviter les réveils inutiles du thread quand aucune app bloquée n'a de
  trafic actif.

## 5. Compiler l'APK en ligne (sans Android Studio)

Le projet inclut `.github/workflows/build.yml`, qui compile automatiquement
un APK de debug via **GitHub Actions** à chaque `push`.

1. Créer un compte GitHub (gratuit) si besoin, puis un nouveau dépôt (public
   ou privé), par exemple `mamy-dataguard`.
2. Envoyer le contenu de ce dossier dans le dépôt. Le plus simple sans rien
   installer : sur la page du dépôt vide, cliquer **"uploading an existing
   file"**, glisser-déposer tout le contenu du dossier `MamyDataGuard`
   (attention à bien garder l'arborescence, y compris le dossier caché
   `.github`), puis **Commit**.
   *(Alternative en ligne de commande : `git init && git remote add origin ... && git add . && git commit -m "init" && git push`.)*
3. Aller dans l'onglet **Actions** du dépôt : le workflow "Build APK" se
   lance automatiquement (~3-5 minutes).
4. Une fois terminé (coche verte), cliquer sur le run, puis en bas de la
   page sur l'artefact **`MamyDataGuard-debug-apk`** pour le télécharger.
   C'est un `.zip` contenant `app-debug.apk`.
5. Transférer cet APK sur le téléphone Android et l'installer (il faudra
   autoriser "Installer des apps inconnues" pour la source utilisée).

Points à savoir :
- Cet APK est signé avec la **clé de debug** générée automatiquement par
  Gradle : parfait pour tester sur ton propre appareil, mais pas destiné au
  Play Store (il faudrait une vraie clé de signature "release").
- Chaque nouveau `push` régénère un nouvel APK automatiquement.
- Alternative sans passer par GitHub : ouvrir le dossier dans un
  environnement cloud comme **GitHub Codespaces** ou **Gitpod**, qui offrent
  un terminal Linux complet en ligne où lancer `./gradlew assembleDebug`
  directement, sans rien installer sur ta machine.

## 6. Permissions demandées et pourquoi

- `INTERNET`, `ACCESS_NETWORK_STATE` : requis par tout `VpnService`.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` : le VPN doit tourner
  en service premier plan avec notification persistante.
- `POST_NOTIFICATIONS` (Android 13+) : pour les alertes de blocage.
- `RECEIVE_BOOT_COMPLETED` : relancer le pare-feu après redémarrage.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` : éviter que le système tue le
  service en arrière-plan.
- `QUERY_ALL_PACKAGES` : nécessaire pour lister toutes les apps installées
  (Android 11+ restreint sinon la visibilité des paquets).
