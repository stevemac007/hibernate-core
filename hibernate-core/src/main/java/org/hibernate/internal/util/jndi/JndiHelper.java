/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2010, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.internal.util.jndi;

import static org.jboss.logging.Logger.Level.DEBUG;
import static org.jboss.logging.Logger.Level.INFO;
import static org.jboss.logging.Logger.Level.TRACE;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import org.hibernate.cfg.Environment;
import org.jboss.logging.BasicLogger;
import org.jboss.logging.LogMessage;
import org.jboss.logging.Message;
import org.jboss.logging.MessageLogger;

public final class JndiHelper {

    private static final Logger LOG = org.jboss.logging.Logger.getMessageLogger(Logger.class,
                                                                                JndiHelper.class.getPackage().getName());

	private JndiHelper() {
	}

	/**
	 * Given a hodge-podge of properties, extract out the ones relevant for JNDI interaction.
	 *
	 * @param properties
	 * @return
	 */
	@SuppressWarnings({ "unchecked" })
	public static Properties extractJndiProperties(Map configurationValues) {
		final Properties jndiProperties = new Properties();

		for ( Map.Entry entry : (Set<Map.Entry>) configurationValues.entrySet() ) {
			if ( !String.class.isInstance( entry.getKey() ) ) {
				continue;
			}
			final String propertyName = (String) entry.getKey();
			final Object propertyValue = entry.getValue();
			if ( propertyName.startsWith( Environment.JNDI_PREFIX ) ) {
				// write the IntialContextFactory class and provider url to the result only if they are
				// non-null; this allows the environmental defaults (if any) to remain in effect
				if ( Environment.JNDI_CLASS.equals( propertyName ) ) {
					if ( propertyValue != null ) {
						jndiProperties.put( Context.INITIAL_CONTEXT_FACTORY, propertyValue );
					}
				}
				else if ( Environment.JNDI_URL.equals( propertyName ) ) {
					if ( propertyValue != null ) {
						jndiProperties.put( Context.PROVIDER_URL, propertyValue );
					}
				}
				else {
					final String passThruPropertyname = propertyName.substring( Environment.JNDI_PREFIX.length() + 1 );
					jndiProperties.put( passThruPropertyname, propertyValue );
				}
			}
		}

		return jndiProperties;
	}

	/**
	 * Do a JNDI lookup.  Mainly we are handling {@link NamingException}
	 *
	 * @param jndiName The namespace of the object to locate
	 * @param context The context in which to resolve the namespace.
	 *
	 * @return The located object; may be null.
	 *
	 * @throws JndiException if a {@link NamingException} occurs
	 */
	public static Object locate(String jndiName, Context context) {
		try {
			return context.lookup( jndiName );
		}
		catch ( NamingException e ) {
			throw new JndiException( "Unable to lookup JNDI name [" + jndiName + "]", e );
		}
	}

	/**
	 * Bind val to name in ctx, and make sure that all intermediate contexts exist.
	 *
	 * @param ctx the root context
	 * @param name the name as a string
	 * @param val the object to be bound
	 *
	 * @throws JndiException if a {@link NamingException} occurs
	 */
	public static void bind(String jndiName, Object value, Context context) {
		try {
            LOG.binding(jndiName);
			context.rebind( jndiName, value );
		}
		catch ( Exception initialException ) {
			// We had problems doing a simple bind operation.  This could very well be caused by missing intermediate
			// contexts, so we attempt to create those intermmediate contexts and bind again
			Name n = tokenizeName( jndiName, context );
			Context intermediateContextBase = context;
			while ( n.size() > 1 ) {
				final String intermediateContextName = n.get( 0 );

				Context intermediateContext = null;
				try {
                    LOG.intermediateLookup(intermediateContextName);
					intermediateContext = (Context) intermediateContextBase.lookup( intermediateContextName );
				}
				catch ( NameNotFoundException handledBelow ) {
					// ok as we will create it below if not found
				}
				catch ( NamingException e ) {
					throw new JndiException( "Unaniticipated error doing intermediate lookup", e );
				}

                if (intermediateContext != null) LOG.foundIntermediateContext(intermediateContextName);
				else {
                    LOG.creatingSubcontextTrace(intermediateContextName);
					try {
						intermediateContext = intermediateContextBase.createSubcontext( intermediateContextName );
					}
					catch ( NamingException e ) {
						throw new JndiException( "Error creating intermediate context [" + intermediateContextName + "]", e );
					}
				}
				intermediateContextBase = intermediateContext;
				n = n.getSuffix( 1 );
			}
            LOG.binding(n);
			try {
				intermediateContextBase.rebind( n, value );
			}
			catch ( NamingException e ) {
				throw new JndiException( "Error performing intermediate bind [" + n + "]", e );
			}
		}
        LOG.boundName(jndiName);
	}

	private static Name tokenizeName(String jndiName, Context context) {
		try {
			return context.getNameParser( "" ).parse( jndiName );
		}
		catch ( NamingException e ) {
			throw new JndiException( "Unable to tokenize JNDI name [" + jndiName + "]", e );
		}
	}





	// todo : remove these once we get the services in place and integrated into the SessionFactory






	public static InitialContext getInitialContext(Properties props) throws NamingException {

		Hashtable hash = extractJndiProperties(props);
        LOG.jndiInitialContextProperties(hash);
		try {
			return hash.size()==0 ?
					new InitialContext() :
					new InitialContext(hash);
		}
		catch (NamingException e) {
            LOG.error(LOG.unableToObtainInitialContext(), e);
			throw e;
		}
	}

	/**
	 * Bind val to name in ctx, and make sure that all intermediate contexts exist.
	 *
	 * @param ctx the root context
	 * @param name the name as a string
	 * @param val the object to be bound
	 * @throws NamingException
	 */
	public static void bind(Context ctx, String name, Object val) throws NamingException {
		try {
            LOG.binding(name);
			ctx.rebind(name, val);
		}
		catch (Exception e) {
			Name n = ctx.getNameParser("").parse(name);
			while ( n.size() > 1 ) {
				String ctxName = n.get(0);

				Context subctx=null;
				try {
                    LOG.lookup(ctxName);
					subctx = (Context) ctx.lookup(ctxName);
				}
				catch (NameNotFoundException nfe) {}

				if (subctx!=null) {
                    LOG.foundSubcontext(ctxName);
					ctx = subctx;
				}
				else {
                    LOG.creatingSubcontextInfo(ctxName);
					ctx = ctx.createSubcontext(ctxName);
				}
				n = n.getSuffix(1);
			}
            LOG.binding(n);
			ctx.rebind(n, val);
		}
        LOG.boundName(name);
	}

    /**
     * Interface defining messages that may be logged by the outer class
     */
    @MessageLogger
    interface Logger extends BasicLogger {

        @LogMessage( level = TRACE )
        @Message( value = "Binding : %s" )
        void binding( Object jndiName );

        @LogMessage( level = DEBUG )
        @Message( value = "Bound name: %s" )
        void boundName( String jndiName );

        @LogMessage( level = INFO )
        @Message( value = "Creating subcontext: %s" )
        void creatingSubcontextInfo( String intermediateContextName );

        @LogMessage( level = TRACE )
        @Message( value = "Creating subcontext: %s" )
        void creatingSubcontextTrace( String intermediateContextName );

        @LogMessage( level = TRACE )
        @Message( value = "Found intermediate context: %s" )
        void foundIntermediateContext( String intermediateContextName );

        @LogMessage( level = DEBUG )
        @Message( value = "Found subcontext: %s" )
        void foundSubcontext( String ctxName );

        @LogMessage( level = TRACE )
        @Message( value = "Intermediate lookup: %s" )
        void intermediateLookup( String intermediateContextName );

        @LogMessage( level = INFO )
        @Message( value = "JNDI InitialContext properties:%s" )
        void jndiInitialContextProperties( Hashtable hash );

        @LogMessage( level = TRACE )
        @Message( value = "Lookup: %s" )
        void lookup( String ctxName );

        @Message( value = "Could not obtain initial context" )
        Object unableToObtainInitialContext();
    }
}
