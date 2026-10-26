#!/usr/bin/env python3
"""Applies the custom-apps hook to a deodexed Launcher_WP3 smali tree (in place).

1. Reroutes the launcher's resource-id lookups through dev.cabinwright.launcher.ExtRes:
   each `invoke-virtual {receiver, id}, <Class>;->method(I)...` becomes
   `invoke-static {receiver, id}, ExtRes;->method(<Class>;I)...` - same registers and
   result, so it is a one-for-one swap. ExtRes passes real ids straight through.
2. Calls ExtApps.addTo(list, owner) in AllMenuEntryList.load() right after the head unit's
   region list is built (adds custom apps).
3. Calls ExtApps.arrange(items, context) wherever All Menus' drawn Item list is final
   (applies Edit Menu's layout), on both of the loader's paths:
   - AllMenuView.addItem() (view/aY), which builds the list from scratch: the path taken
     on the EV6 (the loader's list starts out null). Hooked before its return-void.
   - the loader's merge path (view/aV doInBackground, :cond_261), inside its lock.
   v0/v1 are free at both points.
4. Adds the broadcast dev.cabinwright.action.RELOAD to All Menus' receiver (view/aY registers
   it, view/br handles it): a full rebuild via AllMenuView.t(true), which runs the loader
   and so step 3. The head unit's own allmenu.UPDATE only refreshes the existing items.
5. Calls EditScreen.redirect(context, intent) right before the launcher's startActivity
   at the two places it opens All Menus edit screen (HomeSetting AllMenuEdit, the
   options menu's "Edit Home Icons"): Launcher.aX(I) and AllMenuView.gG(). It swaps in
   Edit Menu when that is installed.

usage: patch.py <smali dir>
Must run on a case-sensitive filesystem (the tree has classes like g and G).
"""
import re
import sys
from pathlib import Path

EXT = "Ldev/cabinwright/launcher/ExtRes;"
OURS = "dev/cabinwright/"

# (receiver class, method, original descriptor tail, static descriptor)
REROUTES = [
    ("Landroid/content/Context;", "getString", "(I)Ljava/lang/String;",
     "(Landroid/content/Context;I)Ljava/lang/String;"),
    ("Landroid/content/res/Resources;", "getString", "(I)Ljava/lang/String;",
     "(Landroid/content/res/Resources;I)Ljava/lang/String;"),
    ("Landroid/content/res/Resources;", "getText", "(I)Ljava/lang/CharSequence;",
     "(Landroid/content/res/Resources;I)Ljava/lang/CharSequence;"),
    ("Landroid/content/res/Resources;", "getDrawable", "(I)Landroid/graphics/drawable/Drawable;",
     "(Landroid/content/res/Resources;I)Landroid/graphics/drawable/Drawable;"),
    ("Landroid/widget/TextView;", "setText", "(I)V", "(Landroid/widget/TextView;I)V"),
    ("Landroid/widget/ImageView;", "setImageResource", "(I)V", "(Landroid/widget/ImageView;I)V"),
    ("Landroid/widget/ImageButton;", "setImageResource", "(I)V", "(Landroid/widget/ImageView;I)V"),
    ("Landroid/view/View;", "setBackgroundResource", "(I)V", "(Landroid/view/View;I)V"),
]

LOAD_FILE = "com/android/launcher3/view/a/g.smali"
LOAD_ANCHOR = ("    invoke-virtual {v0}, Lcom/android/launcher3/view/a/au;->hf()Ljava/util/List;\n"
               "\n"
               "    move-result-object v0\n")
LOAD_HOOK = ("\n    invoke-static {v0, p0}, Ldev/cabinwright/launcher/ExtApps;"
             "->addTo(Ljava/util/List;Lcom/android/launcher3/view/a/g;)V\n")


ARRANGE_FILE = "com/android/launcher3/view/aV.smali"
ARRANGE_ANCHOR = ("    :cond_261\n"
                  "    iget-object v0, p0, Lcom/android/launcher3/view/aV;->tB:Lcom/android/launcher3/view/aY;\n"
                  "\n"
                  "    iget-object v1, p0, Lcom/android/launcher3/view/aV;->tB:Lcom/android/launcher3/view/aY;\n")
ARRANGE_HOOK = ("    :cond_261\n"
                "    iget-object v0, p0, Lcom/android/launcher3/view/aV;->tB:Lcom/android/launcher3/view/aY;\n"
                "    invoke-static {v0}, Lcom/android/launcher3/view/aY;->b(Lcom/android/launcher3/view/aY;)Ljava/util/ArrayList;\n"
                "    move-result-object v0\n"
                "    iget-object v1, p0, Lcom/android/launcher3/view/aV;->tB:Lcom/android/launcher3/view/aY;\n"
                "    invoke-static {v1}, Lcom/android/launcher3/view/aY;->q(Lcom/android/launcher3/view/aY;)Landroid/content/Context;\n"
                "    move-result-object v1\n"
                "    invoke-static {v0, v1}, Ldev/cabinwright/launcher/ExtApps;"
                "->arrange(Ljava/util/ArrayList;Landroid/content/Context;)V\n\n")

ADDITEM_FILE = "com/android/launcher3/view/aY.smali"
ADDITEM_ANCHOR = ("    goto :goto_91\n"
                  "\n"
                  "    .line 1376\n"
                  "    :cond_ad\n"
                  "    return-void\n")
ADDITEM_HOOK = ("    goto :goto_91\n"
                "\n"
                "    .line 1376\n"
                "    :cond_ad\n"
                "    iget-object v0, p0, Lcom/android/launcher3/view/aY;->rB:Ljava/util/ArrayList;\n"
                "    invoke-static {p0}, Lcom/android/launcher3/view/aY;->q(Lcom/android/launcher3/view/aY;)Landroid/content/Context;\n"
                "    move-result-object v1\n"
                "    invoke-static {v0, v1}, Ldev/cabinwright/launcher/ExtApps;"
                "->arrange(Ljava/util/ArrayList;Landroid/content/Context;)V\n"
                "    return-void\n")

RELOAD = "dev.cabinwright.action.RELOAD"
FILTER_FILE = "com/android/launcher3/view/aY.smali"
FILTER_ANCHOR = ('    const-string v2, "com.hkmc.system.app.allmenu.UPDATE"\n'
                 "\n"
                 "    invoke-virtual {v1, v2}, Landroid/content/IntentFilter;->addAction(Ljava/lang/String;)V\n")
FILTER_HOOK = ('\n    const-string v2, "%s"\n'
               "    invoke-virtual {v1, v2}, Landroid/content/IntentFilter;->addAction(Ljava/lang/String;)V\n" % RELOAD)
RECEIVER_FILE = "com/android/launcher3/view/br.smali"
RECEIVER_ANCHOR = ("    .line 904\n"
                   '    const-string v1, "com.hkmc.system.app.allmenu.UPDATE"\n')
# v0 holds the action; the original code calls v0.equals() right after this too
RECEIVER_HOOK = ('    const-string v1, "%s"\n'
                 "    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z\n"
                 "    move-result v1\n"
                 "    if-eqz v1, :cabinwright_not_reload\n"
                 "    iget-object v1, p0, Lcom/android/launcher3/view/br;->tB:Lcom/android/launcher3/view/aY;\n"
                 "    const/4 v2, 0x1\n"
                 "    invoke-virtual {v1, v2}, Lcom/android/launcher3/view/aY;->t(Z)V\n"
                 "    goto :goto_a\n"
                 "    :cabinwright_not_reload\n\n" % RELOAD)

REDIRECT = ("    invoke-static {%s, %s}, Ldev/cabinwright/launcher/EditScreen;"
            "->redirect(Landroid/content/Context;Landroid/content/Intent;)V\n\n")
# Launcher.aX(I): one startActivity shared by all its menu actions; v0 is the intent
EDIT_LAUNCHER_FILE = "com/android/launcher3/view/Launcher.smali"
EDIT_LAUNCHER_ANCHOR = ("    .line 918\n"
                        "    invoke-virtual {p0, v0}, Landroid/app/Activity;->startActivity(Landroid/content/Intent;)V\n")
EDIT_LAUNCHER_HOOK = ("    .line 918\n" + REDIRECT % ("p0", "v0")
                      + EDIT_LAUNCHER_ANCHOR.split("\n", 1)[1])
# AllMenuView.gG(): v0 is the Launcher, v1 the intent
EDIT_ALLMENU_FILE = "com/android/launcher3/view/aY.smali"
EDIT_ALLMENU_ANCHOR = ("    .line 1394\n"
                       "    iget-object v0, p0, Lcom/android/launcher3/view/aY;->tR:Lcom/android/launcher3/view/Launcher;\n"
                       "\n"
                       "    invoke-virtual {v0, v1}, Landroid/app/Activity;->startActivity(Landroid/content/Intent;)V\n")
EDIT_ALLMENU_HOOK = EDIT_ALLMENU_ANCHOR.replace("\n\n    invoke-virtual", "\n\n" + REDIRECT % ("v0", "v1") + "    invoke-virtual")


def hook(root, rel, anchor, new, what):
    f = root / rel
    text = f.read_text()
    if text.count(anchor) != 1:
        sys.exit("patch.py: %s isn't the expected code - refusing" % what)
    f.write_text(text.replace(anchor, new))
    print("hooked " + what)


def main():
    root = Path(sys.argv[1])
    counts = {}
    for f in root.rglob("*.smali"):
        rel = f.relative_to(root).as_posix()
        if rel.startswith(OURS):
            continue
        text = f.read_text()
        new = text
        for cls, name, tail, static in REROUTES:
            pat = re.compile(r"invoke-virtual(/range)? \{([^}]*)\}, " + re.escape(cls + "->" + name + tail))
            new, n = pat.subn(lambda m: "invoke-static%s {%s}, %s->%s%s" % (
                m.group(1) or "", m.group(2), EXT, name, static), new)
            if n:
                counts[cls + name] = counts.get(cls + name, 0) + n
        if new != text:
            f.write_text(new)

    for k, v in sorted(counts.items()):
        print("%5d  %s" % (v, k))
    hook(root, LOAD_FILE, LOAD_ANCHOR, LOAD_ANCHOR + LOAD_HOOK, "AllMenuEntryList.load()")
    hook(root, ARRANGE_FILE, ARRANGE_ANCHOR, ARRANGE_HOOK + ARRANGE_ANCHOR.split("\n", 1)[1], "AllMenuView loader")
    hook(root, ADDITEM_FILE, ADDITEM_ANCHOR, ADDITEM_HOOK, "AllMenuView.addItem()")
    hook(root, FILTER_FILE, FILTER_ANCHOR, FILTER_ANCHOR + FILTER_HOOK, "All Menus receiver filter")
    hook(root, RECEIVER_FILE, RECEIVER_ANCHOR, RECEIVER_HOOK + RECEIVER_ANCHOR, "All Menus receiver")
    hook(root, EDIT_LAUNCHER_FILE, EDIT_LAUNCHER_ANCHOR, EDIT_LAUNCHER_HOOK, "Launcher edit screen")
    hook(root, EDIT_ALLMENU_FILE, EDIT_ALLMENU_ANCHOR, EDIT_ALLMENU_HOOK, "All Menus edit screen")


if __name__ == "__main__":
    main()
