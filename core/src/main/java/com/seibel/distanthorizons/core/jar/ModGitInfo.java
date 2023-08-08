package com.seibel.distanthorizons.core.jar;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.json.JsonFormat;

/**
 * Get info on the git for the mod <br>
 * Warning: Gets generated on runtime
 *
 * @author coolGi
 */
public final class ModGitInfo {
    static {
        // Warning: Atm, this file is in the common subproject as the processResources task in gradle doesnt work for core
        String s = JarUtils.convertInputStreamToString(JarUtils.accessFile("build_info.json"));

        Config jsonObject = Config.inMemory();
        JsonFormat.minimalInstance().createParser().parse(s, jsonObject, ParsingMode.REPLACE);

        Git_Main_Commit = jsonObject.get("git_main_commit");
        Git_Core_Commit = jsonObject.get("git_core_commit");
        Git_Main_Branch = jsonObject.get("git_main_branch");
    }

    public static final String Git_Main_Commit;
    public static final String Git_Core_Commit;
    public static final String Git_Main_Branch;
}
