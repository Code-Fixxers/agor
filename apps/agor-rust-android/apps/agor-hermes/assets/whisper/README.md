Do not place Whisper model binaries in this asset directory.

Whisper models are large and should be distributed as downloadable artifacts,
then configured in the app's Voice Transcription settings.

For local artifact preparation, run:

```bash
scripts/fetch-whisper-model.sh base.en
```
