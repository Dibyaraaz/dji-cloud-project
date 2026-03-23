// backend/src/auth.js
const jwt = require('jsonwebtoken');
const bcrypt = require('bcrypt');
const { getUserByUsername } = require('./database');

const JWT_SECRET = process.env.JWT_SECRET || 'your-secret-key-change-this-in-production';
const JWT_EXPIRES_IN = '24h';

/**
 * Generate JWT token for user
 */
function generateToken(user) {
  const payload = {
    id: user.id,
    username: user.username,
    role: user.role
  };
  
  return jwt.sign(payload, JWT_SECRET, { expiresIn: JWT_EXPIRES_IN });
}

/**
 * Verify JWT token
 */
function verifyToken(token) {
  try {
    return jwt.verify(token, JWT_SECRET);
  } catch (error) {
    return null;
  }
}

/**
 * Authenticate user with username and password
 */
async function authenticateUser(username, password) {
  const user = await getUserByUsername(username);
  
  if (!user) {
    return { success: false, message: 'Invalid username or password' };
  }

  const isValid = await bcrypt.compare(password, user.password_hash);
  
  if (!isValid) {
    return { success: false, message: 'Invalid username or password' };
  }

  const token = generateToken(user);
  
  return {
    success: true,
    token,
    user: {
      id: user.id,
      username: user.username,
      email: user.email,
      role: user.role
    }
  };
}

/**
 * Middleware to protect routes
 */
function authMiddleware(req, res, next) {
  const authHeader = req.headers.authorization;
  
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'No token provided' });
  }

  const token = authHeader.substring(7);
  const decoded = verifyToken(token);
  
  if (!decoded) {
    return res.status(401).json({ error: 'Invalid or expired token' });
  }

  req.user = decoded;
  next();
}

/**
 * Middleware to check if user has required role
 */
function requireRole(...roles) {
  return (req, res, next) => {
    if (!req.user) {
      return res.status(401).json({ error: 'Authentication required' });
    }

    if (!roles.includes(req.user.role)) {
      return res.status(403).json({ error: 'Insufficient permissions' });
    }

    next();
  };
}

/**
 * Hash password (for creating new users)
 */
async function hashPassword(password) {
  return bcrypt.hash(password, 10);
}

module.exports = {
  generateToken,
  verifyToken,
  authenticateUser,
  authMiddleware,
  requireRole,
  hashPassword
};
