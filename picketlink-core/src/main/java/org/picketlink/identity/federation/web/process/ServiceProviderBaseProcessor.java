/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
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
package org.picketlink.identity.federation.web.process;

import static org.picketlink.identity.federation.core.util.StringUtil.isNotNull;

import java.io.IOException;
import java.net.URL;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import javax.servlet.http.HttpServletRequest;

import org.picketlink.identity.federation.PicketLinkLogger;
import org.picketlink.identity.federation.PicketLinkLoggerFactory;
import org.picketlink.identity.federation.core.audit.PicketLinkAuditHelper;
import org.picketlink.identity.federation.core.config.ProviderType;
import org.picketlink.identity.federation.core.config.SPType;
import org.picketlink.identity.federation.core.exceptions.ConfigurationException;
import org.picketlink.identity.federation.core.exceptions.ParsingException;
import org.picketlink.identity.federation.core.exceptions.ProcessingException;
import org.picketlink.identity.federation.core.interfaces.TrustKeyConfigurationException;
import org.picketlink.identity.federation.core.interfaces.TrustKeyManager;
import org.picketlink.identity.federation.core.interfaces.TrustKeyProcessingException;
import org.picketlink.identity.federation.core.saml.v2.common.SAMLDocumentHolder;
import org.picketlink.identity.federation.core.saml.v2.holders.IssuerInfoHolder;
import org.picketlink.identity.federation.core.saml.v2.impl.DefaultSAML2HandlerRequest;
import org.picketlink.identity.federation.core.saml.v2.impl.DefaultSAML2HandlerResponse;
import org.picketlink.identity.federation.core.saml.v2.interfaces.SAML2Handler;
import org.picketlink.identity.federation.core.saml.v2.interfaces.SAML2Handler.HANDLER_TYPE;
import org.picketlink.identity.federation.core.saml.v2.interfaces.SAML2HandlerRequest;
import org.picketlink.identity.federation.core.saml.v2.interfaces.SAML2HandlerRequest.GENERATE_REQUEST_TYPE;
import org.picketlink.identity.federation.core.saml.v2.interfaces.SAML2HandlerResponse;
import org.picketlink.identity.federation.core.util.StringUtil;
import org.picketlink.identity.federation.web.constants.GeneralConstants;
import org.picketlink.identity.federation.web.core.HTTPContext;

/**
 * A processor util at the SP
 *
 * @author Anil.Saldhana@redhat.com
 * @since Oct 27, 2009
 */
public class ServiceProviderBaseProcessor {
    
    protected static final PicketLinkLogger logger = PicketLinkLoggerFactory.getLogger();

    protected boolean postBinding;

    protected String serviceURL;

    protected String identityURL;

    protected ProviderType spConfiguration;

    protected TrustKeyManager keyManager;

    protected String issuer = null;
    
    protected PicketLinkAuditHelper auditHelper = null;

    public static final String IDP_KEY = "idp.key";

    /**
     * Construct
     *
     * @param postBinding Whether it is the Post Binding
     * @param serviceURL Service URL of the SP
     */
    public ServiceProviderBaseProcessor(boolean postBinding, String serviceURL) {
        this.postBinding = postBinding;
        this.serviceURL = serviceURL;
    }

    /**
     * Set the SP configuration
     *
     * @param sp
     */
    public void setConfiguration(ProviderType sp) {
        this.spConfiguration = sp;
    }

    /**
     * Set the {@code TrustKeyManager}
     *
     * @param tkm
     */
    public void setTrustKeyManager(TrustKeyManager tkm) {
        this.keyManager = tkm;
    }

    /**
     * Set the Identity URL
     *
     * @param identityURL
     */
    public void setIdentityURL(String identityURL) {
        this.identityURL = identityURL;
    }

    /**
     * Set a separate issuer that is different from the service url
     *
     * @param issuer
     */
    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
    
    /**
     * Set the {@link PicketLinkAuditHelper}
     * @param helper
     */
    public void setAuditHelper(PicketLinkAuditHelper helper){
        this.auditHelper = helper;
    }

    public SAML2HandlerResponse process(HTTPContext httpContext, Set<SAML2Handler> handlers, Lock chainLock)
            throws ProcessingException, IOException, ParsingException, ConfigurationException {

        logger.trace("SAML Handlers are: " + handlers);

        // Neither saml request nor response from IDP
        // So this is a user request

        // Ask the handler chain to generate the saml request

        // Create the request/response
        SAML2HandlerRequest saml2HandlerRequest = getSAML2HandlerRequest(null, httpContext);
        saml2HandlerRequest.addOption(GeneralConstants.CONTEXT_PATH, httpContext.getServletContext().getContextPath());
        saml2HandlerRequest.addOption(GeneralConstants.SUPPORTS_SIGNATURES, this.spConfiguration.isSupportsSignature());
        
        SAML2HandlerResponse saml2HandlerResponse = new DefaultSAML2HandlerResponse();

        saml2HandlerResponse.setPostBindingForResponse(postBinding);
        saml2HandlerResponse.setDestination(identityURL);
        
        // if the request is a GLO. Check if there is a specific URL for logout.
        if (isLogOutRequest(httpContext)) {
            String logoutUrl = ((SPType) this.spConfiguration).getLogoutUrl();
            
            if (logoutUrl != null) {
                saml2HandlerResponse.setDestination(logoutUrl);
            }
        }

        // Reset the state
        try {

            chainLock.lock();

            for (SAML2Handler handler : handlers) {
                handler.reset();
                if (saml2HandlerResponse.isInError()) {
                    httpContext.getResponse().sendError(saml2HandlerResponse.getErrorCode());
                    break;
                }

                if (isLogOutRequest(httpContext))
                    saml2HandlerRequest.setTypeOfRequestToBeGenerated(GENERATE_REQUEST_TYPE.LOGOUT);
                else
                    saml2HandlerRequest.setTypeOfRequestToBeGenerated(GENERATE_REQUEST_TYPE.AUTH);
                handler.generateSAMLRequest(saml2HandlerRequest, saml2HandlerResponse);

                logger.trace("Finished Processing handler: " + handler.getClass().getCanonicalName());
            }
        } catch (ProcessingException pe) {
            logger.error(pe);
            throw logger.samlHandlerChainProcessingError(pe);
        } finally {
            chainLock.unlock();
        }

        return saml2HandlerResponse;
    }

    protected SAML2HandlerRequest getSAML2HandlerRequest(SAMLDocumentHolder documentHolder, HTTPContext httpContext) {
        IssuerInfoHolder holder = null;

        if (issuer == null) {
            holder = new IssuerInfoHolder(this.serviceURL);
        } else {
            holder = new IssuerInfoHolder(issuer);
        }

        return new DefaultSAML2HandlerRequest(httpContext, holder.getIssuer(), documentHolder, HANDLER_TYPE.SP);
    }

    protected boolean isLogOutRequest(HTTPContext httpContext) {
        HttpServletRequest request = httpContext.getRequest();
        String gloStr = request.getParameter(GeneralConstants.GLOBAL_LOGOUT);
        return isNotNull(gloStr) && "true".equalsIgnoreCase(gloStr);
    }

    protected URL safeURL(String urlString) {
        try {
            return new URL(urlString);
        } catch (Exception e) {
        }
        return null;
    }

    /**
     * <p>
     * Returns the PublicKey to be used to verify signatures for SAML tokens issued by the IDP.
     * </p>
     *
     * @return
     * @throws org.picketlink.identity.federation.core.interfaces.TrustKeyConfigurationException
     * @throws org.picketlink.identity.federation.core.interfaces.TrustKeyProcessingException
     */
    protected PublicKey getIDPPublicKey() throws TrustKeyConfigurationException, TrustKeyProcessingException {
        if (this.keyManager == null) {
            throw logger.trustKeyManagerMissing();
        }
        String idpValidatingAlias = (String) this.keyManager.getAdditionalOption(ServiceProviderBaseProcessor.IDP_KEY);

        if (StringUtil.isNullOrEmpty(idpValidatingAlias)) {
            idpValidatingAlias = safeURL(spConfiguration.getIdentityURL()).getHost();
        }

        return keyManager.getValidatingKey(idpValidatingAlias);
    }

    protected void setRequestOptions(SAML2HandlerRequest saml2HandlerRequest) throws TrustKeyConfigurationException, TrustKeyProcessingException {
        if (spConfiguration != null) {
            Map<String, Object> requestOptions = new HashMap<String, Object>();

            requestOptions.put(GeneralConstants.CONFIGURATION, spConfiguration);
            
            if(auditHelper != null){
                requestOptions.put(GeneralConstants.AUDIT_HELPER, auditHelper);
            }

            if (keyManager != null) {
                PublicKey validatingKey = getIDPPublicKey();

                requestOptions.put(GeneralConstants.SENDER_PUBLIC_KEY, validatingKey);
                requestOptions.put(GeneralConstants.DECRYPTING_KEY, keyManager.getSigningKey());
            }
            
            requestOptions.put(GeneralConstants.SUPPORTS_SIGNATURES, this.spConfiguration.isSupportsSignature());

            saml2HandlerRequest.setOptions(requestOptions);
        }
    }

}