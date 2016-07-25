package org.exoplatform.commons.file.services.impl;

import org.apache.commons.lang.StringUtils;
import org.exoplatform.commons.api.persistence.ExoTransactional;
import org.exoplatform.commons.file.model.NameSpace;
import org.exoplatform.commons.file.resource.BinaryProvider;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.model.FileInfo;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.commons.file.services.FileStorageException;
import org.exoplatform.commons.file.services.util.FileChecksum;
import org.exoplatform.commons.file.storage.DataStorage;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ValueParam;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;

/**
 * File Service which stores the file metadata in a database, and uses a
 * BinaryProvider to store the file binary.
 * Created by The eXo Platform SAS
 * Author : eXoPlatform exo@exoplatform.com
 */
public class FileServiceImpl implements FileService {

  private static final Log    LOG                = ExoLogger.getLogger(FileServiceImpl.class);

  private static final String Algorithm_PARAM    = "Algorithm";

  private DataStorage dataStorage;

  private BinaryProvider    binaryProvider;

  private FileChecksum        fileChecksum;

  public FileServiceImpl(DataStorage dataStorage,
                         BinaryProvider resourceProvider,
                         InitParams initParams)
      throws Exception {
    this.dataStorage= dataStorage;
    this.binaryProvider = resourceProvider;

    ValueParam algorithmValueParam = null;
    if (initParams != null) {
      algorithmValueParam = initParams.getValueParam(Algorithm_PARAM);
    }

    if (algorithmValueParam == null) {
      this.fileChecksum = new FileChecksum();
    } else {
      this.fileChecksum = new FileChecksum(algorithmValueParam.getValue());
    }
  }

  @Override
  public FileInfo getFileInfo(long id) throws IOException {
    return dataStorage.getFileInfo(id);
  }

  @Override
  public FileItem getFile(long id) throws FileStorageException {
    FileItem fileItem;
    FileInfo fileInfo = dataStorage.getFileInfo(id);

    if (StringUtils.isEmpty(fileInfo.getChecksum())) {
      return null;
    }
    try {
      fileItem = new FileItem(fileInfo, null);
      InputStream inputStream = binaryProvider.getStream(fileInfo.getChecksum());
      fileItem.setInputStream(inputStream);
    }
    catch (Exception e){
      throw new FileStorageException("Cannot get File Item ID="+id,e);
    }

    return fileItem;
  }

  @Override
  @ExoTransactional
  public FileItem writeFile(FileItem file) throws FileStorageException, IOException {
    if (file.getFileInfo() == null || StringUtils.isEmpty(file.getFileInfo().getChecksum())) {
      throw new IllegalArgumentException("Checksum is required to persist the binary");
    }
    FileInfo fileInfo = file.getFileInfo();
      NameSpace nSpace;
      if (fileInfo.getNameSpace() != null && !fileInfo.getNameSpace().isEmpty()) {
        nSpace = dataStorage.getNameSpace(fileInfo.getNameSpace());
      } else {
        nSpace = dataStorage.getNameSpace(NameSpaceServiceImpl.getDefaultNameSpace());
      }
    //Add suffix to checksum
      fileInfo.setChecksum(fileInfo.getChecksum()+getRandom());
      FileStorageTransaction transaction = new FileStorageTransaction(fileInfo, nSpace);
      FileInfo createdFileInfoEntity= transaction.towPhaseCommit(2, file.getAsStream());
      if (createdFileInfoEntity != null) {
        fileInfo.setId(createdFileInfoEntity.getId());
        file.setFileInfo(fileInfo);
        return file;
      }
    return null;
  }

  @Override
  @ExoTransactional
  public FileItem updateFile(FileItem file) throws FileStorageException, IOException {
    if (file.getFileInfo() == null || StringUtils.isEmpty(file.getFileInfo().getChecksum())) {
      throw new IllegalArgumentException("Checksum is required to persist the binary");
    }
    FileInfo fileInfo = file.getFileInfo();
    NameSpace nSpace;
    if (fileInfo.getNameSpace() != null && !fileInfo.getNameSpace().isEmpty()) {
      nSpace = dataStorage.getNameSpace(fileInfo.getNameSpace());
    } else {
      nSpace = dataStorage.getNameSpace(NameSpaceServiceImpl.getDefaultNameSpace());
    }
    FileStorageTransaction transaction = new FileStorageTransaction(fileInfo, nSpace);
    FileInfo createdFileInfoEntity= transaction.towPhaseCommit(0, file.getAsStream());
    if (createdFileInfoEntity != null) {
      fileInfo.setId(createdFileInfoEntity.getId());
      file.setFileInfo(fileInfo);
      return file;
    }
    return null;
  }

  @Override
  public FileInfo deleteFile(long id) {
    FileInfo fileInfo= dataStorage.getFileInfo(id);
    if(fileInfo != null)
    {
      fileInfo.setDeleted(true);
    }
    return dataStorage.updateFileInfo(fileInfo);
  }
  /*Manage two phase commit :file storage and datasource*/
  private class FileStorageTransaction {
    /**
     * Update Operation.
     */
    final int              UPDATE = 0;

    /**
     * Remove Operation.
     */
    final int              REMOVE = 1;

    /**
     * Insert Operation.
     */
    final int              INSERT = 2;

    private FileInfo fileInfo;

    private NameSpace nameSpace;

    public FileStorageTransaction(FileInfo fileInfo, NameSpace nameSpace) {
      this.fileInfo = fileInfo;
      this.nameSpace = nameSpace;
    }

    public FileInfo towPhaseCommit(int operation, InputStream inputStream) throws FileStorageException {
      FileInfo createdFileInfoEntity = null;
      if (operation == INSERT) {
        boolean created = false;
        try {
          binaryProvider.put(fileInfo.getChecksum(), inputStream);
          if (binaryProvider.exists(fileInfo.getChecksum())) {
            created = true;
            createdFileInfoEntity = dataStorage.create(fileInfo, nameSpace);
            return createdFileInfoEntity;
          } else {
            throw new FileStorageException("Error while writing file " + fileInfo.getName());
          }
        } catch (Exception e) {
          try {
            if(created) {
              binaryProvider.remove(fileInfo.getChecksum());
            }
          } catch (IOException e1) {
            LOG.error("Error while rollback writing file");
          }
          throw new FileStorageException("Error while writing file " + fileInfo.getName(), e);
        }

      } else if (operation == REMOVE) {
        fileInfo.setDeleted(true);
        dataStorage.updateFileInfo(fileInfo);
      } else if (operation == UPDATE) {
        try {
          boolean updated = false;
          FileInfo oldFile= dataStorage.getFileInfo(fileInfo.getId());
          if(oldFile == null || oldFile.getChecksum().isEmpty() || !getChecksumPrefix(oldFile.getChecksum()).equals(fileInfo.getChecksum())) {
            fileInfo.setChecksum(fileInfo.getChecksum()+getRandom());
            binaryProvider.put(fileInfo.getChecksum(), inputStream);
            updated = true;
          }
          else{
            fileInfo.setChecksum(oldFile.getChecksum());
          }
          if (updated) {
            dataStorage.createOrphanFile(oldFile);
          }
          if (binaryProvider.exists(fileInfo.getChecksum())) {
            createdFileInfoEntity =dataStorage.updateFileInfo(fileInfo);
            return createdFileInfoEntity;
          } else {
            throw new FileStorageException("Error while writing file " + fileInfo.getName());
          }
        } catch (Exception e) {
          try {
            binaryProvider.remove(fileInfo.getChecksum());
          } catch (IOException e1) {
            LOG.error("Error while rollback writing file");
          }
          throw new FileStorageException("Error while writing file " + fileInfo.getName(), e);
        }
      }
      return null;
    }
  }
  /**Internal methods*/
  private long getRandom(){
    SecureRandom secureRandom = new SecureRandom();
    long n= secureRandom.nextInt();
    return Math.abs(n);
  }

  private String getChecksumPrefix(String checksum){
    String prefix ="";
    if(checksum != null && !checksum.isEmpty()){
      prefix= checksum.substring(0, 32);
    }
    return prefix ;
  }
}
