package com.seibel.distanthorizons.core.jar;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.json.JsonFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Get info on the git for the mod <br>
 * Warning: Gets generated on runtime
 *
 * @author coolGi
 */
public final class ModGitInfo 
{
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String FILE_NAME = "build_info.json";
	
    static 
    {
		String gitMainCommit = "UNKNOWN";
	    String gitMainBranch = "UNKNOWN";
		String gitCoreCommit = "UNKNOWN";
		
		try
		{
			// Warning: Atm, this file is in the common subproject as the processResources task in gradle doesn't work for core
			String jsonString = JarUtils.convertInputStreamToString(JarUtils.accessFile(FILE_NAME));
			
			Config jsonObject = Config.inMemory();
			JsonFormat.minimalInstance().createParser().parse(jsonString, jsonObject, ParsingMode.REPLACE);
			
			gitMainCommit = jsonObject.get("git_main_commit");
			gitMainBranch = jsonObject.get("git_core_commit");
			gitCoreCommit = jsonObject.get("git_main_branch");
		}
		catch (Exception | Error e)
		{
			LOGGER.warn("Unable to get the Git information from "+FILE_NAME);
		}

        Git_Main_Commit = gitMainCommit;
        Git_Core_Commit = gitMainBranch;
        Git_Main_Branch = gitCoreCommit;
    }

    public static final String Git_Main_Commit;
    public static final String Git_Core_Commit;
    public static final String Git_Main_Branch;
}
