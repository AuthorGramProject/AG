from pathlib import Path

path = Path("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
text = path.read_text("utf-8")

old = """                } else if (id == AUTHORGRAM_KEY_SETTINGS) {
                if (id ==     org.telegram.messenger.authorgram.AuthorGramKeyDialog.show(
                if (id ==             getParentActivity(),
                if (id ==             currentAccount,
                if (id ==             dialog_id
                if (id ==     );
                if (id == }
"""

new = """                } else if (id == AUTHORGRAM_KEY_SETTINGS) {
                    org.telegram.messenger.authorgram.AuthorGramKeyDialog.show(
                            getParentActivity(),
                            currentAccount,
                            dialog_id
                    );

                    return;
                }
"""

if text.count(old) != 1:
    raise RuntimeError(f"Malformed key handler count: {text.count(old)}")

text = text.replace(old, new, 1)
path.write_text(text, "utf-8")

if text.count("AUTHORGRAM_KEY_SETTINGS") != 3:
    raise RuntimeError("AuthorGram key handler validation failed")
if "if (id ==     org.telegram" in text:
    raise RuntimeError("Malformed handler remains")

print("AUTHORGRAM KEY HANDLER FIX: PASS")
