/*
 * (C) Copyright 2006-2009 Nuxeo SA (http://nuxeo.com/) and others.
 *
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
 *
 * Contributors:
 *     Nuxeo - initial API and implementation
 *
 * $Id$
 */

package org.nuxeo.ecm.webdav.resource;

import static javax.ws.rs.core.Response.Status.OK;
import static javax.ws.rs.core.Response.Status.FORBIDDEN;

import java.io.UnsupportedEncodingException;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.HEAD;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import net.java.dev.webdav.jaxrs.methods.COPY;
import net.java.dev.webdav.jaxrs.methods.LOCK;
import net.java.dev.webdav.jaxrs.methods.MKCOL;
import net.java.dev.webdav.jaxrs.methods.MOVE;
import net.java.dev.webdav.jaxrs.methods.PROPPATCH;
import net.java.dev.webdav.jaxrs.methods.UNLOCK;
import net.java.dev.webdav.jaxrs.xml.elements.ActiveLock;
import net.java.dev.webdav.jaxrs.xml.elements.Depth;
import net.java.dev.webdav.jaxrs.xml.elements.HRef;
import net.java.dev.webdav.jaxrs.xml.elements.LockRoot;
import net.java.dev.webdav.jaxrs.xml.elements.LockScope;
import net.java.dev.webdav.jaxrs.xml.elements.LockToken;
import net.java.dev.webdav.jaxrs.xml.elements.LockType;
import net.java.dev.webdav.jaxrs.xml.elements.MultiStatus;
import net.java.dev.webdav.jaxrs.xml.elements.Owner;
import net.java.dev.webdav.jaxrs.xml.elements.Prop;
import net.java.dev.webdav.jaxrs.xml.elements.PropStat;
import net.java.dev.webdav.jaxrs.xml.elements.Status;
import net.java.dev.webdav.jaxrs.xml.elements.TimeOut;
import net.java.dev.webdav.jaxrs.xml.properties.LockDiscovery;

import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.util.URIUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.Path;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentSecurityException;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.model.PropertyNotFoundException;
import org.nuxeo.ecm.webdav.backend.Backend;
import org.nuxeo.ecm.webdav.backend.BackendHelper;
import org.nuxeo.ecm.webdav.jaxrs.Win32CreationTime;
import org.nuxeo.ecm.webdav.jaxrs.Win32FileAttributes;
import org.nuxeo.ecm.webdav.jaxrs.Win32LastAccessTime;
import org.nuxeo.ecm.webdav.jaxrs.Win32LastModifiedTime;

/**
 * An existing resource corresponds to an existing object (folder or file) in the repository.
 */
public class ExistingResource extends AbstractResource {

    public static final String READONLY_TOKEN = "readonly";

    private static final Log log = LogFactory.getLog(ExistingResource.class);

    protected DocumentModel doc;

    protected Backend backend;

    protected ExistingResource(String path, DocumentModel doc, HttpServletRequest request, Backend backend) {
        super(path, request);
        this.doc = doc;
        this.backend = backend;
    }

    @DELETE
    public Response delete() {
        if (backend.isLocked(doc.getRef()) && !backend.canUnlock(doc.getRef())) {
            return Response.status(423).build();
        }

        try {
            backend.removeItem(doc.getRef());
            backend.saveChanges();
            return Response.status(204).build();
        } catch (DocumentSecurityException e) {
            log.error("Can't remove item: " + doc.getPathAsString() + e.getMessage());
            log.debug(e);
            return Response.status(FORBIDDEN).build();
        }
    }

    @COPY
    public Response copy(@HeaderParam("Destination") String dest, @HeaderParam("Overwrite") String overwrite) {
        return copyOrMove("COPY", dest, overwrite);
    }

    @MOVE
    public Response move(@HeaderParam("Destination") String dest, @HeaderParam("Overwrite") String overwrite) {
        if (backend.isLocked(doc.getRef()) && !backend.canUnlock(doc.getRef())) {
            return Response.status(423).build();
        }

        return copyOrMove("MOVE", dest, overwrite);
    }

    private static String encode(byte[] bytes, String encoding) {
        try {
            return new String(bytes, encoding);
        } catch (UnsupportedEncodingException e) {
            throw new NuxeoException("Unsupported encoding " + encoding);
        }
    }

    private Response copyOrMove(String method, @HeaderParam("Destination") String destination,
            @HeaderParam("Overwrite") String overwrite) {

        if (backend.isLocked(doc.getRef()) && !backend.canUnlock(doc.getRef())) {
            return Response.status(423).build();
        }

        destination = encode(destination.getBytes(), "ISO-8859-1");
        try {
            destination = URIUtil.decode(destination);
        } catch (URIException e) {
            throw new NuxeoException(e);
        }

        Backend root = BackendHelper.getBackend("/", request);
        Set<String> names = new HashSet<String>(root.getVirtualFolderNames());
        Path destinationPath = new Path(destination);
        String[] segments = destinationPath.segments();
        int removeSegments = 0;
        for (String segment : segments) {
            if (names.contains(segment)) {
                break;
            } else {
                removeSegments++;
            }
        }
        destinationPath = destinationPath.removeFirstSegments(removeSegments);

        String destPath = destinationPath.toString();
        String davDestPath = destPath;
        Backend destinationBackend = BackendHelper.getBackend(davDestPath, request);
        destPath = destinationBackend.parseLocation(destPath).toString();
        log.debug("to " + davDestPath);

        // Remove dest if it exists and the Overwrite header is set to "T".
        int status = 201;
        if (destinationBackend.exists(davDestPath)) {
            if ("F".equals(overwrite)) {
                return Response.status(412).build();
            }
            destinationBackend.removeItem(davDestPath);
            status = 204;
        }

        // Check if parent exists
        String destParentPath = getParentPath(destPath);
        PathRef destParentRef = new PathRef(destParentPath);
        if (!destinationBackend.exists(getParentPath(davDestPath))) {
            return Response.status(409).build();
        }

        if ("COPY".equals(method)) {
            DocumentModel destDoc = backend.copyItem(doc, destParentRef);
            backend.renameItem(destDoc, getNameFromPath(destPath));
        } else if ("MOVE".equals(method)) {
            if (backend.isRename(doc.getPathAsString(), destPath)) {
                backend.renameItem(doc, getNameFromPath(destPath));
            } else {
                backend.moveItem(doc, destParentRef);
            }
        }
        backend.saveChanges();
        return Response.status(status).build();
    }

    // Properties

    @PROPPATCH
    public Response proppatch(@Context UriInfo uriInfo) {
        if (backend.isLocked(doc.getRef()) && !backend.canUnlock(doc.getRef())) {
            return Response.status(423).build();
        }

        /*
         * JAXBContext jc = Util.getJaxbContext(); Unmarshaller u = jc.createUnmarshaller(); PropertyUpdate
         * propertyUpdate; try { propertyUpdate = (PropertyUpdate) u.unmarshal(request.getInputStream()); } catch
         * (JAXBException e) { return Response.status(400).build(); }
         */
        // Util.printAsXml(propertyUpdate);
        /*
         * List<RemoveOrSet> list = propertyUpdate.list(); final List<PropStat> propStats = new ArrayList<PropStat>();
         * for (RemoveOrSet set : list) { Prop prop = set.getProp(); List<Object> properties = prop.getProperties(); for
         * (Object property : properties) { PropStat propStat = new PropStat(new Prop(property), new Status(OK));
         * propStats.add(propStat); } }
         */

        // @TODO: patch properties if need.
        // Fake proppatch response
        @SuppressWarnings("deprecation")
        final net.java.dev.webdav.jaxrs.xml.elements.Response response = new net.java.dev.webdav.jaxrs.xml.elements.Response(
                new HRef(uriInfo.getRequestUri()), null, null, null, new PropStat(new Prop(new Win32CreationTime()),
                        new Status(OK)), new PropStat(new Prop(new Win32FileAttributes()), new Status(OK)),
                new PropStat(new Prop(new Win32LastAccessTime()), new Status(OK)), new PropStat(new Prop(
                        new Win32LastModifiedTime()), new Status(OK)));

        return Response.status(207).entity(new MultiStatus(response)).build();
    }

    /**
     * We can't MKCOL over an existing resource.
     */
    @MKCOL
    public Response mkcol() {
        return Response.status(405).build();
    }

    @HEAD
    public Response head() {
        return Response.status(200).build();
    }

    @LOCK
    public Response lock(@Context UriInfo uriInfo) {
        String token = null;
        Prop prop = null;
        if (backend.isLocked(doc.getRef())) {
            if (!backend.canUnlock(doc.getRef())) {
                return Response.status(423).build();
            } else {
                token = backend.getCheckoutUser(doc.getRef());
                prop = new Prop(getLockDiscovery(doc, uriInfo));
                return Response.ok().entity(prop).header("Lock-Token", "urn:uuid:" + token).build();
            }
        }

        token = backend.lock(doc.getRef());
        if (READONLY_TOKEN.equals(token)) {
            return Response.status(423).build();
        } else if (StringUtils.isEmpty(token)) {
            return Response.status(400).build();
        }

        prop = new Prop(getLockDiscovery(doc, uriInfo));

        backend.saveChanges();
        return Response.ok().entity(prop).header("Lock-Token", "urn:uuid:" + token).build();
    }

    @UNLOCK
    public Response unlock() {
        if (backend.isLocked(doc.getRef())) {
            if (!backend.canUnlock(doc.getRef())) {
                return Response.status(423).build();
            } else {
                backend.unlock(doc.getRef());
                backend.saveChanges();
                return Response.status(204).build();
            }
        } else {
            // TODO: return an error
            return Response.status(204).build();
        }
    }

    protected LockDiscovery getLockDiscovery(DocumentModel doc, UriInfo uriInfo) {
        LockDiscovery lockDiscovery = null;
        if (doc.isLocked()) {
            String token = backend.getCheckoutUser(doc.getRef());
            lockDiscovery = new LockDiscovery(new ActiveLock(LockScope.EXCLUSIVE, LockType.WRITE, Depth.ZERO,
                    new Owner(token), new TimeOut(10000L), new LockToken(new HRef("urn:uuid:" + token)), new LockRoot(
                            new HRef(uriInfo.getRequestUri()))));
        }
        return lockDiscovery;
    }

    protected PropStatBuilderExt getPropStatBuilderExt(DocumentModel doc, UriInfo uriInfo) throws
            URIException {
        Date lastModified = getTimePropertyWrapper(doc, "dc:modified");
        Date creationDate = getTimePropertyWrapper(doc, "dc:created");
        String displayName = URIUtil.encodePath(backend.getDisplayName(doc));
        PropStatBuilderExt props = new PropStatBuilderExt();
        props.lastModified(lastModified).creationDate(creationDate).displayName(displayName).status(OK);
        if (doc.isFolder()) {
            props.isCollection();
        } else {
            String mimeType = "application/octet-stream";
            long size = 0;
            BlobHolder bh = doc.getAdapter(BlobHolder.class);
            if (bh != null) {
                Blob blob = bh.getBlob();
                if (blob != null) {
                    size = blob.getLength();
                    mimeType = blob.getMimeType();
                }
            }
            if (StringUtils.isEmpty(mimeType) || "???".equals(mimeType)) {
                mimeType = "application/octet-stream";
            }
            props.isResource(size, mimeType);
        }
        return props;
    }

    protected Date getTimePropertyWrapper(DocumentModel doc, String name) {
        Object property;
        try {
            property = doc.getPropertyValue(name);
        } catch (PropertyNotFoundException e) {
            property = null;
            log.debug("Can't get property " + name + " from document " + doc.getId());
        }

        if (property != null) {
            return ((Calendar) property).getTime();
        } else {
            return new Date();
        }
    }

}
