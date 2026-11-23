# Déclic

Coach de remise en forme : séances guidées qui s'adaptent à ton niveau et à ton objectif, démonstrations animées, musique, suivi des progrès et badges.

## Installer sur Android

1. Sur ton téléphone, ouvre **https://github.com/alexarzel/declic/releases/latest/download/declic.apk**
2. Ouvre le fichier téléchargé et accepte l'installation (Android demande d'autoriser l'installation depuis ton navigateur la première fois).

Ensuite, à chaque nouvelle version, l'appli affiche « Une mise à jour est disponible » au démarrage. Touche « Mettre à jour », puis ouvre le fichier téléchargé. Tes données sont conservées.

## Comment c'est fait

- `web/app.html` : toute l'appli (HTML, CSS, JavaScript), la même que la version web.
- `android/` : une coquille Android qui affiche cette page.
- `.github/workflows/android.yml` : à chaque envoi sur `main`, GitHub construit l'APK, le signe et le publie dans les *Releases*. Le numéro de version suit le numéro de build.
- La clé de signature est stockée dans les secrets du dépôt (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Sans elle, les mises à jour ne s'installent pas par-dessus l'ancienne version.
