"""EmailSender port and sandbox implementation.

The port (abstract base) is the stable interface. The sandbox sender persists +
logs the email so the stack runs offline with no SMTP/SendGrid account needed.
A real provider implementation swaps in behind the same port; its key comes from
env (EMAIL_API_KEY), never hardcoded.
"""

import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass

logger = logging.getLogger(__name__)


@dataclass
class EmailMessage:
    to_user_id: int
    subject: str
    body: str
    notification_type: str
    dedup_key: str


class EmailSender(ABC):
    """Port: send an email notification and return the delivery status string."""

    @abstractmethod
    async def send(self, message: EmailMessage) -> str:
        """Return 'SENT' on success, 'PENDING_RECIPIENT' if address unknown, 'FAILED' otherwise."""


class SandboxEmailSender(EmailSender):
    """Dev sender: renders + logs the email; no real SMTP/API call.

    Always returns 'SENT' — proving the broker→consumer→sender path end to end.
    Address resolution (UserRegistered) is deferred to when User's relay is added.
    """

    async def send(self, message: EmailMessage) -> str:
        logger.info(
            "SANDBOX email | user_id=%d | type=%s | dedup_key=%s | subject=%r",
            message.to_user_id,
            message.notification_type,
            message.dedup_key,
            message.subject,
        )
        logger.debug("SANDBOX email body:\n%s", message.body)
        return "SENT"
