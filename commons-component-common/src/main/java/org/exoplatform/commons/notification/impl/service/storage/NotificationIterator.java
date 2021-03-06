/*
 * Copyright (C) 2003-2015 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.commons.notification.impl.service.storage;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;

import org.exoplatform.commons.api.notification.model.WebNotificationFilter;
import org.exoplatform.commons.notification.impl.AbstractService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Defines the NotificationInterator for Notification message nodes
 * 
 * Created by The eXo Platform SAS
 * Author : eXoPlatform
 *          exo@exoplatform.com
 * Jan 26, 2015  
 */
public class NotificationIterator implements Iterator<Node> {

  private static final Log LOG = ExoLogger.getLogger(NotificationIterator.class);

  private int offset;
  
  private int limit;
  
  private NodeIterator parentNotifIter;
  
  private NodeIterator notifIter;
  
  private WebNotificationFilter filter;

  public NotificationIterator(WebNotificationFilter filter, Node parentNode, int offset, int limit) {
    this.offset = offset;
    this.limit = limit;
    this.filter = filter;
    try {
      this.parentNotifIter = AbstractService.getNodeIteratorOrderDESC(parentNode);
      if (this.parentNotifIter.hasNext()) {
        this.notifIter = AbstractService.getNodeIteratorOrderDESC(this.parentNotifIter.nextNode());
      }
    } catch (RepositoryException e) {
      this.parentNotifIter = null;
    }
  }
  
  public NodeIterator getCurrentDateIterator() throws Exception {
    while (! this.notifIter.hasNext() && this.parentNotifIter.hasNext()) {
      this.notifIter = AbstractService.getNodeIteratorOrderDESC(this.parentNotifIter.nextNode());
    }
    return this.notifIter;
  }

  @Override
  public boolean hasNext() {
    try {
      return getCurrentDateIterator().hasNext();
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public Node next() {
    return this.notifIter.nextNode();
  }
  
  @Override
  public void remove() {
  }

  public List<Node> nodes() {
    if (this.parentNotifIter == null) {
      return Collections.emptyList();
    }
    
    List<Node> nodes = new LinkedList<Node>();
    //
    while (hasNext() && this.limit > 0) {
      Node node = next();
      try {
        if (this.filter.isOnPopover() && !node.getProperty(AbstractService.NTF_SHOW_POPOVER).getBoolean()) {
          continue;
        }
      } catch (Exception e) {
        LOG.debug("Failed to get value of property ntf:showPopover ", e);
      }
      if (this.offset > 0) {
        this.offset--;
        continue;
      }
      nodes.add(node);
      this.limit--;
    }
    return nodes;
  }
  
}
