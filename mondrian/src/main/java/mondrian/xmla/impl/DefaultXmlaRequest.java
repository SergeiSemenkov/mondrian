/*
* This software is subject to the terms of the Eclipse Public License v1.0
* Agreement, available at the following URL:
* http://www.eclipse.org/legal/epl-v10.html.
* You must accept the terms of that agreement to use this software.
*
* Copyright (c) 2002-2017 Hitachi Vantara.
* Copyright (C) 2019 Topsoft
* Copyright (C) 2021-2022 Sergei Semenkov
* All rights reserved.
*/

package mondrian.xmla.impl;

import mondrian.olap.Util;
import mondrian.xmla.*;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import org.w3c.dom.*;

import java.util.*;

import static org.olap4j.metadata.XmlaConstants.Method;

/**
 * Default implementation of {@link mondrian.xmla.XmlaRequest} by DOM API.
 *
 * @author Gang Chen
 */
public class DefaultXmlaRequest
    implements XmlaRequest, XmlaConstants
{
    private static final Logger LOGGER =
        LogManager.getLogger(DefaultXmlaRequest.class);

    private static final String MSG_INVALID_XMLA = "Invalid XML/A message";

    /* common content */
    private Method method;
    private Map<String, String> properties;
    private final String roleName;

    /* EXECUTE content */
    private String statement;
    private boolean drillthrough;
    private String command;
    private Map<String, String> parameters = Collections.unmodifiableMap(new HashMap<String, String>());

    /* EXECUTE ALTER content */
    private ServerObject serverObject;
    private String objectDefinition;

    /* DISCOVER contnet */
    private String requestType;
    private Map<String, Object> restrictions;

    private final String username;
    private final String password;
    private final String sessionId;

    public DefaultXmlaRequest(
        final Element xmlaRoot,
        final String roleName,
        final String username,
        final String password,
        final String sessionId)
        throws XmlaException
    {
        init(xmlaRoot);
        this.roleName = roleName;
        this.username = username;
        this.password = password;
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public Method getMethod() {
        return method;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public Map<String, Object> getRestrictions() {
        if (method != Method.DISCOVER) {
            throw new IllegalStateException(
                "Only METHOD_DISCOVER has restrictions");
        }
        return restrictions;
    }

    public String getStatement() {
        if (method != Method.EXECUTE) {
            throw new IllegalStateException(
                "Only METHOD_EXECUTE has statement");
        }
        return statement;
    }

    public String getRoleName() {
        return roleName;
    }

    public String getCommand() {
        return this.command;
    }

    public String getRequestType() {
        if (method != Method.DISCOVER) {
            throw new IllegalStateException(
                "Only METHOD_DISCOVER has requestType");
        }
        return requestType;
    }

    public boolean isDrillThrough() {
        if (method != Method.EXECUTE) {
            throw new IllegalStateException(
                "Only METHOD_EXECUTE determines drillthrough");
        }
        return drillthrough;
    }

    public ServerObject getServerObject() {
        return this.serverObject;
    }

    public String getObjectDefinition() {
        return this.objectDefinition;
    }



    protected final void init(Element xmlaRoot) throws XmlaException {
        if (NS_XMLA.equals(xmlaRoot.getNamespaceURI())) {
            String lname = xmlaRoot.getLocalName();
            if ("Discover".equals(lname)) {
                method = Method.DISCOVER;
                initDiscover(xmlaRoot);
            } else if ("Execute".equals(lname)) {
                method = Method.EXECUTE;
                initExecute(xmlaRoot);
            } else {
                // Note that is code will never be reached because
                // the error will be caught in
                // DefaultXmlaServlet.handleSoapBody first
                StringBuilder buf = new StringBuilder(100);
                buf.append(MSG_INVALID_XMLA);
                buf.append(": Bad method name \"");
                buf.append(lname);
                buf.append("\"");
                throw new XmlaException(
                    CLIENT_FAULT_FC,
                    HSB_BAD_METHOD_CODE,
                    HSB_BAD_METHOD_FAULT_FS,
                    Util.newError(buf.toString()));
            }
        } else {
            // Note that is code will never be reached because
            // the error will be caught in
            // DefaultXmlaServlet.handleSoapBody first
            StringBuilder buf = new StringBuilder(100);
            buf.append(MSG_INVALID_XMLA);
            buf.append(": Bad namespace url \"");
            buf.append(xmlaRoot.getNamespaceURI());
            buf.append("\"");
            throw new XmlaException(
                CLIENT_FAULT_FC,
                HSB_BAD_METHOD_NS_CODE,
                HSB_BAD_METHOD_NS_FAULT_FS,
                Util.newError(buf.toString()));
        }
    }

    private void initDiscover(Element discoverRoot) throws XmlaException {
        Element[] childElems =
            XmlaUtil.filterChildElements(
                discoverRoot,
                NS_XMLA,
                "RequestType");
        if (childElems.length != 1) {
            StringBuilder buf = new StringBuilder(100);
            buf.append(MSG_INVALID_XMLA);
            buf.append(": Wrong number of RequestType elements: ");
            buf.append(childElems.length);
            throw new XmlaException(
                CLIENT_FAULT_FC,
                HSB_BAD_REQUEST_TYPE_CODE,
                HSB_BAD_REQUEST_TYPE_FAULT_FS,
                Util.newError(buf.toString()));
        }
        requestType = XmlaUtil.textInElement(childElems[0]); // <RequestType>

        childElems =
            XmlaUtil.filterChildElements(
                discoverRoot,
                NS_XMLA,
                "Properties");
        if (childElems.length != 1) {
            StringBuilder buf = new StringBuilder(100);
            buf.append(MSG_INVALID_XMLA);
            buf.append(": Wrong number of Properties elements: ");
            buf.append(childElems.length);
            throw new XmlaException(
                CLIENT_FAULT_FC,
                HSB_BAD_PROPERTIES_CODE,
                HSB_BAD_PROPERTIES_FAULT_FS,
                Util.newError(buf.toString()));
        }
        initProperties(childElems[0]); // <Properties><PropertyList>

        childElems =
            XmlaUtil.filterChildElements(
                discoverRoot,
                NS_XMLA,
                "Restrictions");
        if (childElems.length != 1) {
            StringBuilder buf = new StringBuilder(100);
            buf.append(MSG_INVALID_XMLA);
            buf.append(": Wrong number of Restrictions elements: ");
            buf.append(childElems.length);
            throw new XmlaException(
                CLIENT_FAULT_FC,
                HSB_BAD_RESTRICTIONS_CODE,
                HSB_BAD_RESTRICTIONS_FAULT_FS,
                Util.newError(buf.toString()));
        }
        initRestrictions(childElems[0]); // <Restriciotns><RestrictionList>
    }

    private void initExecute(Element executeRoot) throws XmlaException {
        Element[] childElems =
            XmlaUtil.filterChildElements(
                executeRoot,
                NS_XMLA,
                "Command");
        if (childElems.length != 1) {
            StringBuilder buf = new StringBuilder(100);
            buf.append(MSG_INVALID_XMLA);
            buf.append(": Wrong number of Command elements: ");
            buf.append(childElems.length);
            throw new XmlaException(
                CLIENT_FAULT_FC,
                HSB_BAD_COMMAND_CODE,
                HSB_BAD_COMMAND_FAULT_FS,
                Util.newError(buf.toString()));
        }
        initCommand(childElems[0]); // <Command><Statement>

        childElems =
            XmlaUtil.filterChildElements(
                executeRoot,
                NS_XMLA,
                "Properties");
        if (childElems.length != 1) {
            StringBuilder buf = new StringBuilder(100);
            buf.append(MSG_INVALID_XMLA);
            buf.append(": Wrong number of Properties elements: ");
            buf.append(childElems.length);
            throw new XmlaException(
                CLIENT_FAULT_FC,
                HSB_BAD_PROPERTIES_CODE,
                HSB_BAD_PROPERTIES_FAULT_FS,
                Util.newError(buf.toString()));
        }
        initProperties(childElems[0]); // <Properties><PropertyList>

        childElems =
                XmlaUtil.filterChildElements(
                        executeRoot,
                        NS_XMLA,
                        "Parameters");
        if(childElems.length > 0) {
            initParameters(childElems[0]); // <Parameters>
        }
    }

    private void initRestrictions(Element restrictionsRoot)
        throws XmlaException
    {
        Map<String, List<String>> restrictions =
            new HashMap<String, List<String>>();
        Element[] childElems =
            XmlaUtil.filterChildElements(
                restrictionsRoot,
                NS_XMLA,
                "RestrictionList");
        if (childElems.length == 1) {
            NodeList nlst = childElems[0].getChildNodes();
            for (int i = 0, nlen = nlst.getLength(); i < nlen; i++) {
                Node n = nlst.item(i);
                if (n instanceof Element) {
                    Element e = (Element) n;
                    if (NS_XMLA.equals(e.getNamespaceURI())) {
                        String key = e.getLocalName();

                        List<String> values;
                        if (restrictions.containsKey(key)) {
                            values = restrictions.get(key);
                        } else {
                            values = new ArrayList<String>();
                            restrictions.put(key, values);
                        }

                        NodeList propertyValues = e.getChildNodes();
                        for (int j = 0, pvlen = propertyValues.getLength(); j < pvlen; j++) {
                            String value = "";
                            Node vn = propertyValues.item(j);
                            if (vn instanceof Element) {
                                Element ve = (Element) vn;
                                value = XmlaUtil.textInElement(ve);
                            } else {
                                value = XmlaUtil.textInElement(e);
                            }
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug(
                                        "DefaultXmlaRequest.initRestrictions: "
                                                + " key=\""
                                                + key
                                                + "\", value=\""
                                                + value
                                                + "\"");
                            }

                            values.add(value);
                        }
                    }
                }
            }
        } else if (childElems.length > 1) {
            StringBuilder buf = new StringBuilder(100);
            buf.append(MSG_INVALID_XMLA);
            buf.append(": Wrong number of RestrictionList elements: ");
            buf.append(childElems.length);
            throw new XmlaException(
                CLIENT_FAULT_FC,
                HSB_BAD_RESTRICTION_LIST_CODE,
                HSB_BAD_RESTRICTION_LIST_FAULT_FS,
                Util.newError(buf.toString()));
        }

        // If there is a Catalog property,
        // we have to consider it a constraint as well.
        String key =
            org.olap4j.metadata.XmlaConstants
                .Literal.CATALOG_NAME.name();

        if (this.properties.containsKey(key)
            && !restrictions.containsKey(key))
        {
            List<String> values;
            values = new ArrayList<String>();
            restrictions.put(this.properties.get(key), values);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "DefaultXmlaRequest.initRestrictions: "
                    + " key=\""
                    + key
                    + "\", value=\""
                    + this.properties.get(key)
                    + "\"");
            }
        }

        this.restrictions = (Map) Collections.unmodifiableMap(restrictions);
    }

    private void initProperties(Element propertiesRoot) throws XmlaException {
        // Case-insensitive, as Analysis Services treats property names: the
        // OLE DB provider (MSOLAP, which the Power BI gateway queries through)
        // sends its init properties in lower case, e.g. "sspropinitappname",
        // and a case-sensitive EffectiveUserName lookup would silently run a
        // trusted gateway's requests as the gateway itself.
        Map<String, String> properties =
            new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        Element[] childElems =
            XmlaUtil.filterChildElements(
                propertiesRoot,
                NS_XMLA,
                "PropertyList");
        if (childElems.length == 1) {
            NodeList nlst = childElems[0].getChildNodes();
            for (int i = 0, nlen = nlst.getLength(); i < nlen; i++) {
                Node n = nlst.item(i);
                if (n instanceof Element) {
                    Element e = (Element) n;
                    if (NS_XMLA.equals(e.getNamespaceURI())) {
                        String key = e.getLocalName();
                        String value = XmlaUtil.textInElement(e);

                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(
                                "DefaultXmlaRequest.initProperties: "
                                + " key=\""
                                + key
                                + "\", value=\""
                                + value
                                + "\"");
                        }

                        properties.put(key, value);
                    }
                }
            }
        } else if (childElems.length > 1) {
            StringBuilder buf = new StringBuilder(100);
            buf.append(MSG_INVALID_XMLA);
            buf.append(": Wrong number of PropertyList elements: ");
            buf.append(childElems.length);
            throw new XmlaException(
                CLIENT_FAULT_FC,
                HSB_BAD_PROPERTIES_LIST_CODE,
                HSB_BAD_PROPERTIES_LIST_FAULT_FS,
                Util.newError(buf.toString()));
        } else {
        }
        this.properties = Collections.unmodifiableMap(properties);
    }

    private void initParameters(Element parameterElement) throws XmlaException {
        Map<String, String> parameters = new HashMap<String, String>();

        NodeList nlst = parameterElement.getChildNodes();
        for (int i = 0, nlen = nlst.getLength(); i < nlen; i++) {
            Node n = nlst.item(i);
            if (n instanceof Element) {
                String name = null;
                Element[] nameElems =
                        XmlaUtil.filterChildElements(
                                (Element)n,
                                NS_XMLA,
                                "Name");
                if(nameElems.length > 0) {
                    name = XmlaUtil.textInElement(nameElems[0]);

                    String value = null;
                    //TODO: Implement xsi:type
                    Element[] valueElems =
                            XmlaUtil.filterChildElements(
                                    (Element)n,
                                    NS_XMLA,
                                    "Value");
                    if(nameElems.length > 0) {
                        value = XmlaUtil.textInElement(valueElems[0]);
                    }

                    parameters.put(name, value);
                }
            }
        }
        this.parameters = Collections.unmodifiableMap(parameters);
    }


    private void initCommand(Element commandRoot) throws XmlaException {
        Element[] commandElements =
                XmlaUtil.filterChildElements(
                        commandRoot,
                        null,
                        null);
        if (commandElements.length != 1) {
            StringBuilder buf = new StringBuilder(100);
            buf.append(MSG_INVALID_XMLA);
            buf.append(": Wrong number of Command children elements: ");
            buf.append(commandElements.length);
            throw new XmlaException(
                    CLIENT_FAULT_FC,
                    HSB_BAD_COMMAND_CODE,
                    HSB_BAD_COMMAND_FAULT_FS,
                    Util.newError(buf.toString()));
        }
        else {
            command = commandElements[0].getLocalName();
            if(command != null && command.toUpperCase().equals("STATEMENT")) {
                statement = XmlaUtil.textInElement(commandElements[0]).replaceAll("\\r", "");
                drillthrough = statement.toUpperCase().indexOf("DRILLTHROUGH") != -1;
            }
            else if(command != null && command.toUpperCase().equals("ALTER")) {
                Element[] objectElements =
                        XmlaUtil.filterChildElements(
                                commandElements[0],
                                null,
                                "Object");
                if(objectElements.length > 1 ) {
                    StringBuilder buf = new StringBuilder(100);
                    buf.append(MSG_INVALID_XMLA);
                    buf.append(": Wrong number of Objects elements: ");
                    buf.append(objectElements.length);
                    throw new XmlaException(
                            CLIENT_FAULT_FC,
                            HSB_BAD_PROPERTIES_LIST_CODE,
                            HSB_BAD_PROPERTIES_LIST_FAULT_FS,
                            Util.newError(buf.toString()));
                }
                if(objectElements.length > 0) {
                    String databaseId = null;

                    Element[] databaseIdElements =
                            XmlaUtil.filterChildElements(
                                    objectElements[0],
                                    null,
                                    "DatabaseID");

                    if(databaseIdElements.length > 1 ) {
                        StringBuilder buf = new StringBuilder(100);
                        buf.append(MSG_INVALID_XMLA);
                        buf.append(": Wrong number of DatabaseID elements: ");
                        buf.append(databaseIdElements.length);
                        throw new XmlaException(
                                CLIENT_FAULT_FC,
                                HSB_BAD_PROPERTIES_LIST_CODE,
                                HSB_BAD_PROPERTIES_LIST_FAULT_FS,
                                Util.newError(buf.toString()));
                    }
                    if(databaseIdElements.length > 0 ) {
                        databaseId = XmlaUtil.textInElement(databaseIdElements[0]).replaceAll("\\r", "");
                    }

                    this.serverObject = new ServerObject(databaseId);
                }
                Element[] objectDefinitionElements =
                        XmlaUtil.filterChildElements(
                                commandElements[0],
                                null,
                                "ObjectDefinition");
                if(objectDefinitionElements.length > 1 ) {
                    StringBuilder buf = new StringBuilder(100);
                    buf.append(MSG_INVALID_XMLA);
                    buf.append(": Wrong number of ObjectDefinition elements: ");
                    buf.append(objectDefinitionElements.length);
                    throw new XmlaException(
                            CLIENT_FAULT_FC,
                            HSB_BAD_PROPERTIES_LIST_CODE,
                            HSB_BAD_PROPERTIES_LIST_FAULT_FS,
                            Util.newError(buf.toString()));
                }
                if(objectDefinitionElements.length > 0 ) {
                    this.objectDefinition = XmlaUtil.textInElement(objectDefinitionElements[0]);
                }
            }
            else if(command != null && command.toUpperCase().equals("CANCEL")) {
                ;
            }
            else {
                StringBuilder buf = new StringBuilder(100);
                buf.append(MSG_INVALID_XMLA);
                buf.append(": Wrong child of Command elements: ");
                buf.append(command);
                throw new XmlaException(
                        CLIENT_FAULT_FC,
                        HSB_BAD_COMMAND_CODE,
                        HSB_BAD_COMMAND_FAULT_FS,
                        Util.newError(buf.toString()));
            }
        }
    }

    public void setProperty(String key, String value) {
        Map<String, String> newProperties =
            new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        newProperties.putAll(this.properties);
        newProperties.put(key, value);
        this.properties = Collections.unmodifiableMap(newProperties);
    }

    private String authenticatedUser = null;
    public void setAuthenticatedUser(String authenticatedUser) {
        this.authenticatedUser = authenticatedUser;
    }
    public String getAuthenticatedUser() {
        return this.authenticatedUser;
    }

    private String[] authenticatedUserGroups = null;
    public void setAuthenticatedUserGroups(String[] authenticatedUserGroups) {
        this.authenticatedUserGroups = authenticatedUserGroups;
    }
    public String[] getAuthenticatedUserGroups() {
        return this.authenticatedUserGroups;
    }

}

// End DefaultXmlaRequest.java
