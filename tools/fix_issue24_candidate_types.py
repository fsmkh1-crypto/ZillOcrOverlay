from pathlib import Path
p = Path('app/src/main/java/kr/co/zillocr/overlay/LearningManagerActivity.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('OpenAiTranslationProvider.PendingSpeakerCandidate', 'OpenAiTranslationProvider.Companion.PendingSpeakerCandidate')
s = s.replace('OpenAiTranslationProvider.PendingAliasCandidate', 'OpenAiTranslationProvider.Companion.PendingAliasCandidate')
p.write_text(s, encoding='utf-8')
print('fixed issue24 candidate type references')
