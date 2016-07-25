/*
 * Copyright (C) 2016 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.commons.file.services.job;

import org.exoplatform.commons.file.model.FileInfo;
import org.exoplatform.commons.file.model.OrphanFile;
import org.exoplatform.commons.file.resource.BinaryProvider;
import org.exoplatform.commons.file.storage.DataStorage;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by The eXo Platform SAS Author : eXoPlatform exo@exoplatform.com
 */
public class FileStorageCleanJob implements Job {
  private static Log    Log              = ExoLogger.getLogger(FileStorageCleanJob.class);

  private static int    defaultRetention = 30;

  private static AtomicBoolean enabled = new AtomicBoolean(true);

  private static AtomicBoolean started = new AtomicBoolean(false);

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    if(Log.isDebugEnabled()) {
      Log.debug("Start to clean old FileStorage...");
    }
    if(!enabled.get())
      return;
    try {
      DataStorage dataStorage = CommonsUtils.getService(DataStorage.class);
      BinaryProvider binaryProvider = CommonsUtils.getService(BinaryProvider.class);
      JobDataMap jdatamap = context.getJobDetail().getJobDataMap();
      int retention = defaultRetention;
      if (jdatamap != null) {
        String valueParam = jdatamap.getString(FileStorageCronJob.RETENTION_PARAM);
        try {
          retention = Integer.parseInt(valueParam);
        } catch (NumberFormatException ex) {
          Log.warn("Invalid param retention-time");
        }
        //-1: means never deleted
        if(retention == -1)
          return;
        valueParam = jdatamap.getString(FileStorageCronJob.ENABLED_PARAM);
        try {
          enabled.set(Boolean.valueOf(valueParam));
        } catch (Exception ex) {
          Log.warn("Invalid param enabled");
        }
      }
      started.set(true);
      List<FileInfo> list = dataStorage.getAllDeletedFiles(daysAgo(retention));
      for (FileInfo file : list) {
        try {
          binaryProvider.remove(file.getChecksum());
          dataStorage.deleteFileInfo(file.getId());
        } catch (IOException e) {
          Log.warn("Enable to remove file name" + e.getMessage());
        }
      }
      List<OrphanFile> noParent = dataStorage.getAllOrphanFile(daysAgo(retention));
      for (OrphanFile file : noParent) {
        try {
          binaryProvider.remove(file.getChecksum());
          dataStorage.deleteOrphanFile(file.getId());
        } catch (IOException e) {
          Log.warn("Enable to remove file name" + e.getMessage());
        }
      }
      if (Log.isDebugEnabled()) {
        Log.debug("End to clean old FileStorage...");
      }
    }
    finally {
      started.set(false);
    }
  }

  private static Date daysAgo(int days) {
    GregorianCalendar gc = new GregorianCalendar();
    gc.add(Calendar.DATE, -days);
    return gc.getTime();
  }

  public static AtomicBoolean isEnabled() {
    return enabled;
  }

  public static void setEnabled(Boolean enabled) {
    FileStorageCleanJob.enabled.getAndSet(enabled);
  }

  public static AtomicBoolean isStarted() {
    return started;
  }

}
