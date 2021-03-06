/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.raptor.backup;

import com.facebook.presto.raptor.backup.metadata.BackupMetadataDao;
//import com.facebook.presto.raptor.backup.metadata.BackupMetadataManager;
import com.facebook.presto.raptor.metadata.ForMetadata;
import com.facebook.presto.raptor.util.DaoSupplier;
import com.facebook.presto.spi.PrestoException;

import com.filepool.fplibrary.FPClip;
import com.filepool.fplibrary.FPFileInputStream;
import com.filepool.fplibrary.FPLibraryConstants;
import com.filepool.fplibrary.FPLibraryException;
import com.filepool.fplibrary.FPPool;
import com.filepool.fplibrary.FPRetentionClass;
import com.filepool.fplibrary.FPRetentionClassContext;
import com.filepool.fplibrary.FPTag;

import io.airlift.log.Logger;

import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.IDBI;

import javax.inject.Inject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static com.facebook.presto.raptor.RaptorErrorCode.RAPTOR_BACKUP_ERROR;
import static com.facebook.presto.raptor.RaptorErrorCode.RAPTOR_BACKUP_NOT_FOUND;
import static com.facebook.presto.raptor.backup.metadata.BackupSchemaDaoUtil.createCenteraBackupMetadataTablesWithRetry;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class CenteraBackupStore implements BackupStore
{
    private final CenteraBackupConfig config;
    private final Properties configProperties;
    private long lastConfigAccessTime = 0L;
    private final String appVersion = "1.0";
    private final String appName = "PrestoRaptorBackup";
    private final String vendorName = "Innominds";
    private final String tagName = "RaptorBackup";
    private final String clipName = "RaptorShard";

    private final IDBI dbi;
    private final BackupMetadataDao dao;
    // private final BackupMetadataManager backupMetadataManager;
    private final DaoSupplier<BackupMetadataDao> backupMetadataDaoSupplier;

    private Logger logger = Logger.get(this.getClass());

    private FPPool thePool = null;

    @Inject
    public CenteraBackupStore(CenteraBackupConfig config, @ForMetadata IDBI dbi,
            DaoSupplier<BackupMetadataDao> backupMetadataDaoSupplier)
    {
        this.config = config;
        this.dbi = requireNonNull(dbi, "dbi is null");
        this.backupMetadataDaoSupplier = requireNonNull(backupMetadataDaoSupplier, "backupMetadataDaoSupplier is null");
        this.dao = backupMetadataDaoSupplier.onDemand();
        this.configProperties = new Properties();
        readConfigProperties();

        createCenteraBackupMetadataTablesWithRetry(dbi);

        try {
            FPPool.RegisterApplication(appName, appVersion);

            // New feature for 2.3 lazy pool open
            FPPool.setGlobalOption(FPLibraryConstants.FP_OPTION_OPENSTRATEGY, FPLibraryConstants.FP_LAZY_OPEN);

            thePool = new FPPool(config.getPoolAddress());
        }
        catch (FPLibraryException e) {
            throw new PrestoException(RAPTOR_BACKUP_ERROR, "Failed to register centra backup application ", e);
        }
    }

    public void readConfigProperties()
    {
        try {
            FileInputStream configIs = new FileInputStream(new File(config.getConfigFilePath()));
            configProperties.load(configIs);
            lastConfigAccessTime = System.currentTimeMillis() / 1000L;
        }
        catch (FileNotFoundException e) {
            throw new PrestoException(RAPTOR_BACKUP_NOT_FOUND, "File " + config.getConfigFilePath() + " not found. " + e);
        }
        catch (IOException e) {
            throw new PrestoException(RAPTOR_BACKUP_ERROR, "Failed to read " + config.getConfigFilePath() + e);
        }
    }

    @Override
    public void backupShard(UUID uuid, File source, String schemaTableName)
    {
        ClipInfo clipInfo = null;
        Long retentionPeriod = null;
        String retentionClass = null;

        try {
            // If the config file has changed in the mean while, reload the properties.
            Path path = Paths.get(config.getConfigFilePath());
            BasicFileAttributes fileAttr = Files.readAttributes(path, BasicFileAttributes.class);

            if ((fileAttr.creationTime().toMillis() / 1000L) > lastConfigAccessTime ||
                    (fileAttr.lastModifiedTime().toMillis() / 1000L) > lastConfigAccessTime) {
                readConfigProperties();
            }
        }
        catch (IOException e) {
            throw new PrestoException(RAPTOR_BACKUP_ERROR, "Failed to read attributes for " + config.getConfigFilePath() + e);
        }

        // Optimistically assume the file can be created
        logger.info("Attempting to Write shard %s to centera.", source.getPath());
        try {
            if (schemaTableName != null) {
                String tableRetentionOption = configProperties.getProperty("retention." + schemaTableName.toLowerCase());

                logger.info("Retention option for table %s is %s ", schemaTableName, tableRetentionOption);

                if (tableRetentionOption != null) {
                    Boolean applyRetention = tableRetentionOption.equalsIgnoreCase("true");

                    if (applyRetention) {
                        logger.info("Getting retention period/class from properties for table %s", schemaTableName);
                        retentionPeriod = getRetentionPeriod(schemaTableName);
                        if (retentionPeriod == null) {
                            retentionClass = getRetentionClass(schemaTableName);
                        }
                    }
                }
            }

            if (retentionPeriod == null && retentionClass == null) {
                logger.info("Retention period or class is not set for table %s ", schemaTableName);
            }

            clipInfo = storeFile(source, retentionPeriod, retentionClass);
            // Write UUID, clipInfo to the metadata
            logger.info("Writing clipInfo for clip %s for shard %s to metadata.", clipInfo.getClipid(), uuid.toString());
            writeClipInfoToMetadata(uuid, clipInfo, retentionClass);
        }
        catch (FPLibraryException e) {
            throw new PrestoException(RAPTOR_BACKUP_ERROR, "Failed to backup shard: " + uuid, e);
        }
    }

    private Long getRetentionPeriod(String schemaTableName)
    {
        String retentionPeriodConfig = configProperties.getProperty("retention.period." + schemaTableName.toLowerCase());

        logger.info("Retention Period specified for table %s is %s ", schemaTableName, retentionPeriodConfig);
        if (retentionPeriodConfig != null) {
            return Long.parseLong(retentionPeriodConfig);
        }
        return null;
    }

    private String getRetentionClass(String schemaTableName)
    {
        return configProperties.getProperty("retention.class." + schemaTableName.toLowerCase());
    }

    @Override
    public void restoreShard(UUID uuid, File target)
    {
        try {
            logger.info("Attempting to retrieve shard with UUID " + uuid.toString() + " from Centera ... ");
            retrieveShard(uuid, target);
        }
        catch (FileNotFoundException e) {
            throw new PrestoException(RAPTOR_BACKUP_NOT_FOUND, "Backup shard not found: " + uuid, e);
        }
        catch (IOException e) {
            throw new PrestoException(RAPTOR_BACKUP_ERROR, "Failed to copy backup shard: " + uuid, e);
        }
        logger.info("Successfully retrieved shard with UUID from Centera: " + uuid.toString());
    }

    @Override
    public boolean deleteShard(UUID uuid)
    {
        String clipId = null;
        boolean deleted = false;

        clipId = dao.getCenteraClipIdForShard(uuid.toString());

        if (clipId == null || clipId.equals("")) {
            throw new PrestoException(RAPTOR_BACKUP_ERROR, "Got null or invalid clipId from Centera for shard " + uuid);
        }

        try {
            if (!FPClip.Exists(thePool, clipId)) {
                throw new IllegalArgumentException("ClipID \"" + clipId + "\" does not exist on this Centera cluster.");
            }
            logger.info("Attempting to delete shard with UUID " + uuid.toString() + " whose clipId is " + clipId
                    + "from Centera: ");
            FPClip.Delete(thePool, clipId);
            logger.info("Successfully deleted shard with UUID " + uuid.toString() + " whose clipId is " + clipId
                    + "from Centera: ");
            deleted = true;
        }
        catch (FPLibraryException e) {
            throw new PrestoException(RAPTOR_BACKUP_ERROR, "Failed to delete shard: " + uuid, e);
        }

        if (deleted) {
            logger.info("Deleting shard info with UUID " + uuid.toString() + " from centera backup metadata");
            dao.deleteCenteraClipInfoForShard(uuid.toString());
        }
        return deleted;
    }

    @Override
    public boolean shardExists(UUID uuid)
    {
        if (dao.getCenteraClipIdForShard(uuid.toString()) != null) {
            return true;
        }
        return false;
    }

    @Override
    public boolean canDeleteShard(UUID uuid)
    {
       // ClipRetentionInfo info = dao.getClipRetentionInfo(uuid.toString());
        Timestamp creationDate;
        long retentionPeriod = 0L;

        String selectRetentionInfo = format("" +
                "SELECT creation_date, retention_period from backup_centera \n" +
                "WHERE shard_uuid = %s", uuid.toString());

        try (Handle handle = dbi.open()) {
            PreparedStatement statement = handle.getConnection().prepareStatement(selectRetentionInfo);
            ResultSet rs = statement.executeQuery();
            creationDate = rs.getTimestamp("creation_date");
            retentionPeriod = rs.getLong("retention_period");
        }
        catch (SQLException e) {
            return false;
        }

        long clipAge = 0L;

        // Get the age of the clip in seconds
        try {
            clipAge = (thePool.getClusterTime() - creationDate.getTime()) / 1000;
        }
        catch (FPLibraryException e) {
            return false;
        }

        return (clipAge >= retentionPeriod);
    }

    private ClipInfo storeFile(File source, Long retentionPeriod, String retentionClass) throws FPLibraryException
    {
        ClipInfo clipInfo = null;
        String clipId = "";

        try {
            // create a new named C-Clip
            FPClip theClip = new FPClip(thePool, clipName);

            // Write out vendor, application and version info
            theClip.setDescriptionAttribute("app-vendor", vendorName);
            theClip.setDescriptionAttribute("app-name", appName);
            theClip.setDescriptionAttribute("app-version", appVersion);

            // Set retention period
            if (retentionPeriod != null) {
                logger.info("Setting retention period " + retentionPeriod + " for shard " + source.getPath());
                theClip.setRetentionPeriod(retentionPeriod);
            }
            else if (retentionClass != null) {
                try {
                    FPRetentionClassContext retentionClassList = thePool.getRetentionClassContext();
                    FPRetentionClass fpRetentionClass = retentionClassList.getNamedClass(retentionClass);
                    logger.info("Setting retention class " + retentionClass + " for shard " + source.getPath());
                    theClip.setRetentionClass(fpRetentionClass);
                    theClip.setRetentionPeriod(fpRetentionClass.getPeriod());
                }
                catch (FPLibraryException e) {
                    logger.info("Retention class %s is invalid. Ignoring and continuing ..", retentionClass);
                }
            }

            FPFileInputStream inputStream = new FPFileInputStream(source);

            FPTag topTag = theClip.getTopTag();

            FPTag newTag = new FPTag(topTag, tagName);

            topTag.Close();

            // Blob size is written to clip, so lets just write out filename.
            newTag.setAttribute("filename", source.getPath());

            // write the binary data for this tag to the Centera
            newTag.BlobWrite(inputStream);

            clipId = theClip.Write();

            clipInfo = new ClipInfo(clipId, source.getPath(), theClip.getDescriptionAttributes());

            inputStream.close();
            newTag.Close();
            theClip.Close();
        }
        catch (FileNotFoundException e) {
            throw new IllegalArgumentException("Could not open file \"" + source.getPath() + "\" for reading");
        }
        catch (IOException e) {
            throw new PrestoException(RAPTOR_BACKUP_ERROR, "Could not read from file " + source.getPath());
        }

        return clipInfo;
    }

    private void retrieveShard(UUID uuid, File saveFilename) throws FileNotFoundException, IOException
    {
        String clipId = "";
        try {
            clipId = dao.getCenteraClipIdForShard(uuid.toString());

            if (clipId == null) {
                throw new PrestoException(RAPTOR_BACKUP_NOT_FOUND, "Backup shard not found: " + uuid);
            }

            // Contact cluster to load C-Clip
            logger.info("Attempting to retrieve C-Clip with clip ID: " + clipId + " ... ");

            FPClip theClip = new FPClip(thePool, clipId, FPLibraryConstants.FP_OPEN_FLAT);

            logger.info("Retrieve of clip " + clipId + " Successful");

            FPTag topTag = theClip.getTopTag();

            // check clip metadata to see if this is 'our' data format
            if (!topTag.getTagName().equals(tagName)) {
                logger.error("This clip was not written by Raptor.");
                logger.error(topTag.getTagName());
                logger.error(tagName);
            }

            // Save blob data to file 'OrigFilename.out'
            FileOutputStream outFile = new FileOutputStream(saveFilename);
            topTag.BlobRead(outFile);

            outFile.close();
            topTag.Close();
            theClip.Close();
        }
        catch (FPLibraryException e) {
            logger.error("Centera SDK Error: " + e.getMessage());
        }
        catch (IOException e) {
            logger.error("IO Error occured: " + e.getMessage());
        }
    }

    private void writeClipInfoToMetadata(UUID uuid, ClipInfo clipInfo, String retentionClass)
    {
        dao.insertCenteraClipInfoForShard(
                uuid.toString(), clipInfo.getClipid(),
                clipInfo.getFilename(), clipInfo.getCreationPoolid(), clipInfo.getModificationPoolid(),
                clipInfo.getRetentionPeriod(), retentionClass,
                clipInfo.getType(), clipInfo.getName(), clipInfo.getCreationDate(), clipInfo.getModificationDate(),
                clipInfo.getCreationProfile(), clipInfo.getModificationProfile(),
                clipInfo.getNumfiles(), clipInfo.getTotalSize(), clipInfo.getNamingScheme(), clipInfo.getNumtags(),
                clipInfo.getAppVendor(), clipInfo.getAppName(), clipInfo.getAppVersion()
                );
    }

    public class ClipInfo
    {
        private String clipId;
        private String fileName;
        private String creationPoolid;
        private String modificationPoolid;
        private long retentionPeriod = 0L;
        private String type;
        private String name;
        private Timestamp creationDate;
        private Timestamp modificationDate;
        private String creationProfile;
        private String modificationProfile;
        private int numFiles;
        private long totalSize;
        private String namingScheme;
        private int numTags;
        private String appVendor;
        private String appName;
        private String appVersion;

        public String getClipid()
        {
            return clipId;
        }

        public void setClipid(String clipid)
        {
            this.clipId = clipid;
        }

        public String getFilename()
        {
            return fileName;
        }

        public void setFilename(String filename)
        {
            this.fileName = filename;
        }

        public String getCreationPoolid()
        {
            return creationPoolid;
        }

        public void setCreationPoolid(String creationPoolid)
        {
            this.creationPoolid = creationPoolid;
        }

        public String getModificationPoolid()
        {
            return modificationPoolid;
        }

        public void setModificationPoolid(String modificationPoolid)
        {
            this.modificationPoolid = modificationPoolid;
        }

        public long getRetentionPeriod()
        {
            return retentionPeriod;
        }

        public void setRetentionPeriod(long retentionPeriod)
        {
            this.retentionPeriod = retentionPeriod;
        }

        public String getType()
        {
            return type;
        }

        public void setType(String type)
        {
            this.type = type;
        }

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public Timestamp getCreationDate()
        {
            return creationDate;
        }

        public void setCreationDate(Timestamp creationDate)
        {
            this.creationDate = creationDate;
        }

        public Timestamp getModificationDate()
        {
            return modificationDate;
        }

        public void setModificationDate(Timestamp modificationDate)
        {
            this.modificationDate = modificationDate;
        }

        public String getCreationProfile()
        {
            return creationProfile;
        }

        public void setCreationProfile(String creationProfile)
        {
            this.creationProfile = creationProfile;
        }

        public String getModificationProfile()
        {
            return modificationProfile;
        }

        public void setModificationProfile(String modificationProfile)
        {
            this.modificationProfile = modificationProfile;
        }

        public int getNumfiles()
        {
            return numFiles;
        }

        public void setNumfiles(int numfiles)
        {
            this.numFiles = numfiles;
        }

        public long getTotalSize()
        {
            return totalSize;
        }

        public void setTotalSize(long totalsize)
        {
            this.totalSize = totalsize;
        }

        public String getNamingScheme()
        {
            return namingScheme;
        }

        public void setNamingScheme(String namingScheme)
        {
            this.namingScheme = namingScheme;
        }

        public int getNumtags()
        {
            return numTags;
        }

        public void setNumtags(int numtags)
        {
            this.numTags = numtags;
        }

        public String getAppVendor()
        {
            return appVendor;
        }

        public void setAppVendor(String appVendor)
        {
            this.appVendor = appVendor;
        }

        public String getAppName()
        {
            return appName;
        }

        public void setAppName(String appName)
        {
            this.appName = appName;
        }

        public String getAppVersion()
        {
            return appVersion;
        }

        public void setAppVersion(String appVersion)
        {
            this.appVersion = appVersion;
        }

        public ClipInfo(String clipId, String filename, String[] clipAttributes)
        {
            setClipid(clipId);
            setFilename(filename);

            if (clipAttributes != null) {
                Map<String, String> attributeMap = new HashMap<String, String>();
                for (int i = 0; i < clipAttributes.length; i += 2) {
                    attributeMap.put(clipAttributes[i], clipAttributes[i + 1]);
                }

                setCreationPoolid(attributeMap.get("creation.poolid"));
                setModificationPoolid(attributeMap.get("modification.poolid"));

                String retentionPeriodAttr = attributeMap.get("retention.period");

                if (retentionPeriodAttr != null) {
                    setRetentionPeriod(Long.parseLong(retentionPeriodAttr));
                }
                setType(attributeMap.get("type"));
                setName(attributeMap.get("name"));

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss z");
                java.util.Date date = null;

                try {
                    date = sdf.parse(attributeMap.get("creation.date"));
                    setCreationDate(new Timestamp(date.getTime()));

                    date = sdf.parse(attributeMap.get("modification.date"));
                    setModificationDate(new Timestamp(date.getTime()));
                }
                catch (ParseException e) {
                    logger.error("Date conversion Error: ", e);
                }

                setCreationProfile(attributeMap.get("creation.profile"));
                setModificationProfile(attributeMap.get("modification.profile"));
                setNumfiles(Integer.valueOf(attributeMap.get("numfiles")));
                setTotalSize(Integer.valueOf(attributeMap.get("totalsize")));
                setNamingScheme(attributeMap.get("naming.scheme"));
                setNumtags(Integer.valueOf(attributeMap.get("numtags")));
                setAppVendor(attributeMap.get("app-vendor"));
                setAppName(attributeMap.get("app-name"));
                setAppVersion(attributeMap.get("app-version"));
            }
        }
    }
}
